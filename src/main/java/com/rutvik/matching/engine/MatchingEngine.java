package com.rutvik.matching.engine;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.Trade;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Front door to the books. Symbols are spread over a fixed number of shards
 * (threads); every command for a symbol goes through that symbol's shard queue,
 * which is what fixes the processing order.
 */
public final class MatchingEngine implements AutoCloseable {

    private final List<Shard> shards = new ArrayList<>();
    private final Map<String, Shard> shardBySymbol = new HashMap<>();
    private final List<BookListener> listeners = new CopyOnWriteArrayList<>();
    private final boolean rejectWhenFull;
    private volatile boolean started;

    /**
     * @param journalDir     null = nothing written to disk
     * @param snapshotEvery  commands per symbol between snapshots, 0 = never
     * @param queueSize      per shard inbound queue
     * @param rejectWhenFull true = fail fast with EngineBusyException when a queue is full, false = block the caller
     */
    public record Options(Path journalDir, boolean fsync, long snapshotEvery, int queueSize, boolean rejectWhenFull) {

        public static Options inMemory() {
            return new Options(null, false, 0, 1 << 16, false);
        }

        public static Options journaled(Path dir) {
            return new Options(dir, false, 0, 1 << 16, false);
        }

        public Options withSnapshotEvery(long n) {
            return new Options(journalDir, fsync, n, queueSize, rejectWhenFull);
        }

        public Options withQueue(int size, boolean reject) {
            return new Options(journalDir, fsync, snapshotEvery, size, reject);
        }
    }

    public MatchingEngine(List<String> symbols, int shardCount, Options options) {
        int count = Math.max(1, Math.min(shardCount, symbols.size()));
        this.rejectWhenFull = options.rejectWhenFull();
        BookListener fanOut = new FanOut();
        for (int i = 0; i < count; i++) {
            shards.add(new Shard("engine-" + i, options.queueSize(), options.snapshotEvery(), fanOut));
        }
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            Shard shard = shards.get(i % count);
            SymbolLog symbolLog = options.journalDir() == null ? null
                    : new SymbolLog(options.journalDir(), symbol, options.fsync());
            shard.addSymbol(symbol, symbolLog);
            shardBySymbol.put(symbol, shard);
        }
    }

    public MatchingEngine(List<String> symbols, int shardCount, Path journalDir, boolean fsync) {
        this(symbols, shardCount, new Options(journalDir, fsync, 0, 1 << 16, false));
    }

    public static MatchingEngine inMemory(List<String> symbols, int shardCount) {
        return new MatchingEngine(symbols, shardCount, Options.inMemory());
    }

    public void addListener(BookListener listener) {
        listeners.add(listener);
    }

    /** Recovers from snapshot + journal (listeners see the recovered events too) and starts the shard threads. */
    public synchronized void start() {
        if (started) {
            return;
        }
        shards.forEach(Shard::recover);
        shards.forEach(Shard::start);
        started = true;
    }

    public CompletableFuture<List<ExecutionReport>> submit(Command command) {
        Shard shard = shardFor(command.symbol());
        CompletableFuture<List<ExecutionReport>> reply = new CompletableFuture<>();
        Shard.Submit task = new Shard.Submit(command, reply);
        if (rejectWhenFull) {
            if (!shard.tryEnqueue(task)) {
                reply.completeExceptionally(new EngineBusyException(command.symbol()));
            }
        } else {
            shard.enqueue(task);
        }
        return reply;
    }

    /** Market data goes through the queue as well, so we never read a book from another thread. */
    public CompletableFuture<BookSnapshot> depth(String symbol, int depth) {
        Shard shard = shardFor(symbol);
        CompletableFuture<BookSnapshot> reply = new CompletableFuture<>();
        shard.enqueue(new Shard.Query(symbol, depth, reply));
        return reply;
    }

    public boolean hasSymbol(String symbol) {
        return shardBySymbol.containsKey(symbol);
    }

    public Set<String> symbols() {
        return shardBySymbol.keySet();
    }

    /** Writes a snapshot of every book that changed since its last one. */
    public void saveSnapshots() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Shard shard : shards) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            shard.enqueue(new Shard.SnapshotNow(done));
            pending.add(done);
        }
        pending.forEach(CompletableFuture::join);
    }

    public int queued(String symbol) {
        return shardFor(symbol).queued();
    }

    public long highestOrderId() {
        return shards.stream().mapToLong(Shard::highestOrderId).max().orElse(0);
    }

    private Shard shardFor(String symbol) {
        if (!started) {
            throw new IllegalStateException("engine not started");
        }
        Shard shard = shardBySymbol.get(symbol);
        if (shard == null) {
            throw new IllegalArgumentException("unknown symbol " + symbol);
        }
        return shard;
    }

    @Override
    public synchronized void close() {
        shards.forEach(Shard::stop);
        started = false;
    }

    private final class FanOut implements BookListener {
        @Override
        public void onReport(ExecutionReport report) {
            for (BookListener l : listeners) {
                l.onReport(report);
            }
        }

        @Override
        public void onTrade(Trade trade) {
            for (BookListener l : listeners) {
                l.onTrade(trade);
            }
        }
    }
}
