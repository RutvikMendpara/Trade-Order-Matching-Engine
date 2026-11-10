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
    private volatile boolean started;

    public MatchingEngine(List<String> symbols, int shardCount, Path journalDir, boolean fsync) {
        int count = Math.max(1, Math.min(shardCount, symbols.size()));
        BookListener fanOut = new FanOut();
        for (int i = 0; i < count; i++) {
            shards.add(new Shard("engine-" + i, 1 << 16, fanOut));
        }
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            Shard shard = shards.get(i % count);
            Journal journal = journalDir == null ? null : new Journal(journalDir.resolve(symbol + ".journal"), fsync);
            shard.addSymbol(symbol, journal);
            shardBySymbol.put(symbol, shard);
        }
    }

    public static MatchingEngine inMemory(List<String> symbols, int shardCount) {
        return new MatchingEngine(symbols, shardCount, null, false);
    }

    public void addListener(BookListener listener) {
        listeners.add(listener);
    }

    /** Replays journals (listeners see the replayed events too) and starts the shard threads. */
    public synchronized void start() {
        if (started) {
            return;
        }
        shards.forEach(Shard::replay);
        shards.forEach(Shard::start);
        started = true;
    }

    public CompletableFuture<List<ExecutionReport>> submit(Command command) {
        Shard shard = shardFor(command.symbol());
        CompletableFuture<List<ExecutionReport>> reply = new CompletableFuture<>();
        shard.enqueue(new Shard.Submit(command, reply));
        return reply;
    }

    /** Snapshots go through the queue as well, so we never read a book from another thread. */
    public CompletableFuture<BookSnapshot> snapshot(String symbol, int depth) {
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
