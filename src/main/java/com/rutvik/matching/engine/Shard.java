package com.rutvik.matching.engine;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderBook;
import com.rutvik.matching.book.Trade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    record Stop() implements Task {
    }

    private static final class SymbolState {
        final OrderBook book;
        final Journal journal;
        long sequence;

        SymbolState(OrderBook book, Journal journal) {
            this.book = book;
            this.journal = journal;
        }
    }

    private record Reply(CompletableFuture<List<ExecutionReport>> future, List<ExecutionReport> reports) {
    }

    private final String name;
    private final BookListener downstream;
    private final BlockingQueue<Task> inbox;
    private final Map<String, SymbolState> symbols = new HashMap<>();
    private final List<ExecutionReport> collected = new ArrayList<>();
    private Thread thread;
    private long highestOrderId;

    Shard(String name, int queueSize, BookListener downstream) {
        this.name = name;
        this.downstream = downstream;
        this.inbox = new ArrayBlockingQueue<>(queueSize);
    }

    void addSymbol(String symbol, Journal journal) {
        symbols.put(symbol, new SymbolState(new OrderBook(symbol, this), journal));
    }

    /** Rebuilds every book from its journal. Must run before start(). */
    void replay() {
        for (SymbolState state : symbols.values()) {
            if (state.journal == null) {
                continue;
            }
            List<JournalCodec.Entry> entries = Journal.recover(state.journal.file());
            for (JournalCodec.Entry entry : entries) {
                apply(state.book, entry.command());
                state.sequence = entry.sequence();
            }
            collected.clear();
            if (!entries.isEmpty()) {
                log.info("{}: replayed {} events, {} orders resting", state.book.symbol(), entries.size(),
                        state.book.orderCount());
            }
        }
    }

    void start() {
        thread = new Thread(this, name);
        thread.start();
    }

    void enqueue(Task task) {
        try {
            inbox.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while submitting", e);
        }
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
            if (state.journal != null) {
                state.journal.close();
            }
        }
    }

    long highestOrderId() {
        return highestOrderId;
    }

    @Override
    public void run() {
        List<Task> batch = new ArrayList<>(BATCH);
        List<Reply> replies = new ArrayList<>(BATCH);
        Set<Journal> dirty = new LinkedHashSet<>();
        boolean running = true;

        while (running) {
            try {
                batch.add(inbox.take());
            } catch (InterruptedException e) {
                break;
            }
            inbox.drainTo(batch, BATCH - 1);

            for (Task task : batch) {
                switch (task) {
                    case Submit s -> handle(s, replies, dirty);
                    case Query q -> {
                        SymbolState state = symbols.get(q.symbol());
                        q.reply().complete(state.book.snapshot(q.depth()));
                    }
                    case Stop ignored -> running = false;
                }
            }

            // don't tell anyone about a command until it's on disk
            for (Journal journal : dirty) {
                journal.flush();
            }
            for (Reply reply : replies) {
                reply.future().complete(reply.reports());
            }
            batch.clear();
            replies.clear();
            dirty.clear();
        }
    }

    private void handle(Submit submit, List<Reply> replies, Set<Journal> dirty) {
        Command command = submit.command();
        SymbolState state = symbols.get(command.symbol());
        try {
            long seq = ++state.sequence;
            if (state.journal != null) {
                state.journal.append(seq, command);
                dirty.add(state.journal);
            }
            apply(state.book, command);
            replies.add(new Reply(submit.reply(), List.copyOf(collected)));
        } catch (RuntimeException e) {
            log.error("{} failed on {}", name, command, e);
            submit.reply().completeExceptionally(e);
        } finally {
            collected.clear();
        }
    }

    private void apply(OrderBook book, Command command) {
        switch (command) {
            case Command.NewOrder n -> {
                highestOrderId = Math.max(highestOrderId, n.orderId());
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
