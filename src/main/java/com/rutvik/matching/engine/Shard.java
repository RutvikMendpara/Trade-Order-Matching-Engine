package com.rutvik.matching.engine;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderBook;
import com.rutvik.matching.book.Trade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One thread, a handful of symbols. The thread is the only thing that ever
 * touches these books so there are no locks anywhere in the matching path.
 */
final class Shard implements Runnable, BookListener {

    private static final Logger log = LoggerFactory.getLogger(Shard.class);
    private static final int BATCH = 512;

    sealed interface Task {
    }

    record Submit(Command command, CompletableFuture<List<ExecutionReport>> reply) implements Task {
    }

    record Query(String symbol, int depth, CompletableFuture<BookSnapshot> reply) implements Task {
    }

    record SnapshotNow(CompletableFuture<Void> reply) implements Task {
    }

    record Stop() implements Task {
    }

    private static final class SymbolState {
        final String symbol;
        final SymbolLog log;
        OrderBook book;
        long sequence;
        long highestOrderId;
        long sinceSnapshot;

        SymbolState(String symbol, OrderBook book, SymbolLog log) {
            this.symbol = symbol;
            this.book = book;
            this.log = log;
        }
    }

    private record Reply(CompletableFuture<List<ExecutionReport>> future, List<ExecutionReport> reports) {
    }

    private final String name;
    private final BookListener downstream;
    private final BlockingQueue<Task> inbox;
    private final long snapshotEvery;
    private final Map<String, SymbolState> symbols = new HashMap<>();
    private final List<ExecutionReport> collected = new ArrayList<>();
    private Thread thread;

    Shard(String name, int queueSize, long snapshotEvery, BookListener downstream) {
        this.name = name;
        this.downstream = downstream;
        this.snapshotEvery = snapshotEvery;
        this.inbox = new ArrayBlockingQueue<>(queueSize);
    }

    void addSymbol(String symbol, SymbolLog symbolLog) {
        symbols.put(symbol, new SymbolState(symbol, new OrderBook(symbol, this), symbolLog));
    }

    /** Rebuilds every book from snapshot + journal. Must run before start(). */
    void recover() {
        for (SymbolState state : symbols.values()) {
            if (state.log == null) {
                continue;
            }
            long started = System.nanoTime();
            state.log.loadSnapshot(this).ifPresent(restored -> {
                state.book = restored.book();
                state.sequence = restored.sequence();
                state.highestOrderId = restored.highestOrderId();
            });
            long fromSnapshot = state.sequence;
            state.sequence = state.log.replay(state.sequence, entry -> apply(state, entry.command()));
            collected.clear();
            if (state.sequence > 0) {
                log.info("{}: recovered to seq {} (snapshot at {}, replayed {}) in {} ms, {} orders resting",
                        state.symbol, state.sequence, fromSnapshot, state.sequence - fromSnapshot,
                        (System.nanoTime() - started) / 1_000_000, state.book.orderCount());
            }
        }
    }

    void start() {
        thread = new Thread(this, name);
        thread.start();
    }

    /** Blocks if the queue is full. Used for control messages. */
    void enqueue(Task task) {
        try {
            inbox.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while submitting", e);
        }
    }

    /** Doesn't block - returns false when the queue is full so the caller can push back on the client. */
    boolean tryEnqueue(Task task) {
        return inbox.offer(task);
    }

    void stop() {
        if (thread != null) {
            enqueue(new Stop());
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        for (SymbolState state : symbols.values()) {
            if (state.log != null) {
                state.log.close();
            }
        }
    }

    long highestOrderId() {
        return symbols.values().stream().mapToLong(s -> s.highestOrderId).max().orElse(0);
    }

    int queued() {
        return inbox.size();
    }

    @Override
    public void run() {
        List<Task> batch = new ArrayList<>(BATCH);
        List<Reply> replies = new ArrayList<>(BATCH);
        List<SymbolState> dirty = new ArrayList<>(); // only a few symbols per shard, a list is fine
        boolean running = true;

        while (running) {
            try {
                batch.add(inbox.take());
            } catch (InterruptedException e) {
                break;
            }
            inbox.drainTo(batch, BATCH - 1);

            CompletableFuture<Void> snapshotRequest = null;
            for (Task task : batch) {
                switch (task) {
                    case Submit s -> handle(s, replies, dirty);
                    case Query q -> q.reply().complete(symbols.get(q.symbol()).book.snapshot(q.depth()));
                    case SnapshotNow s -> snapshotRequest = s.reply();
                    case Stop ignored -> running = false;
                }
            }

            // don't tell anyone about a command until it's on disk
            for (SymbolState state : dirty) {
                state.log.journal().flush();
            }
            for (Reply reply : replies) {
                reply.future().complete(reply.reports());
            }

            for (SymbolState state : symbols.values()) {
                boolean due = snapshotEvery > 0 && state.sinceSnapshot >= snapshotEvery;
                if (state.log != null && (due || (snapshotRequest != null && state.sinceSnapshot > 0))) {
                    takeSnapshot(state);
                }
            }
            if (snapshotRequest != null) {
                snapshotRequest.complete(null);
            }

            batch.clear();
            replies.clear();
            dirty.clear();
        }
    }

    private void takeSnapshot(SymbolState state) {
        long started = System.nanoTime();
        try {
            state.log.snapshot(state.book, state.sequence, state.highestOrderId);
            state.sinceSnapshot = 0;
            log.info("{}: snapshot at seq {} ({} orders) took {} ms", state.symbol, state.sequence,
                    state.book.orderCount(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            // not fatal, the journal still has everything
            log.error("{}: snapshot failed", state.symbol, e);
        }
    }

    private void handle(Submit submit, List<Reply> replies, List<SymbolState> dirty) {
        Command command = submit.command();
        SymbolState state = symbols.get(command.symbol());
        try {
            long seq = ++state.sequence;
            if (state.log != null) {
                state.log.append(seq, command);
                if (!dirty.contains(state)) {
                    dirty.add(state);
                }
            }
            apply(state, command);
            state.sinceSnapshot++;
            replies.add(new Reply(submit.reply(), List.copyOf(collected)));
        } catch (RuntimeException e) {
            log.error("{} failed on {}", name, command, e);
            submit.reply().completeExceptionally(e);
        } finally {
            collected.clear();
        }
    }

    private void apply(SymbolState state, Command command) {
        OrderBook book = state.book;
        switch (command) {
            case Command.NewOrder n -> {
                state.highestOrderId = Math.max(state.highestOrderId, n.orderId());
                book.add(n.toOrder());
            }
            case Command.CancelOrder c -> book.cancel(c.orderId());
            case Command.AmendOrder a -> book.amend(a.orderId(), a.quantity(), a.price());
            case Command.EndOfDay e -> book.expireDayOrders();
        }
    }

    @Override
    public void onReport(ExecutionReport report) {
        collected.add(report);
        downstream.onReport(report);
    }

    @Override
    public void onTrade(Trade trade) {
        downstream.onTrade(trade);
    }
}
