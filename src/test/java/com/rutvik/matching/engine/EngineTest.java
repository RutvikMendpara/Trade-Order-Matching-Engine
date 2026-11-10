package com.rutvik.matching.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderStatus;
import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;
import com.rutvik.matching.book.Trade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {

    private static final List<String> SYMBOLS = List.of("INFY", "TCS", "RELIANCE", "HDFCBANK");

    @TempDir
    Path dir;

    static class Collector implements BookListener {
        final List<Trade> trades = Collections.synchronizedList(new ArrayList<>());
        final List<ExecutionReport> reports = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onReport(ExecutionReport report) {
            reports.add(report);
        }

        @Override
        public void onTrade(Trade trade) {
            trades.add(trade);
        }

        List<Trade> tradesFor(String symbol) {
            synchronized (trades) {
                return trades.stream().filter(t -> t.symbol().equals(symbol)).toList();
            }
        }
    }

    static List<Command> randomFlow(long seed, int count) {
        Random random = new Random(seed);
        List<Command> commands = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < count; i++) {
            String symbol = SYMBOLS.get(random.nextInt(SYMBOLS.size()));
            int roll = random.nextInt(10);
            if (roll < 7 || id < 10) {
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                boolean market = random.nextInt(20) == 0;
                commands.add(new Command.NewOrder(id, "c" + random.nextInt(20), "o" + id, symbol, side,
                        market ? OrderType.MARKET : OrderType.LIMIT, market ? TimeInForce.IOC : TimeInForce.GTC,
                        market ? 0 : 10_000 + random.nextInt(40) * 5, 1 + random.nextInt(100)));
                id++;
            } else if (roll < 9) {
                commands.add(new Command.CancelOrder(1 + random.nextInt((int) id - 1), symbol));
            } else {
                commands.add(new Command.AmendOrder(1 + random.nextInt((int) id - 1), symbol,
                        1 + random.nextInt(100), 10_000 + random.nextInt(40) * 5));
            }
        }
        return commands;
    }

    private static void runAll(MatchingEngine engine, List<Command> commands) {
        List<CompletableFuture<List<ExecutionReport>>> futures = new ArrayList<>();
        for (Command c : commands) {
            futures.add(engine.submit(c));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    @Test
    void restartRebuildsTheSameBooks() throws Exception {
        List<Command> flow = randomFlow(42, 20_000);

        List<BookSnapshot> before = new ArrayList<>();
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 2, dir, false)) {
            engine.start();
            runAll(engine, flow);
            for (String s : SYMBOLS) {
                before.add(engine.depth(s, 1000).get());
            }
        }

        try (MatchingEngine restarted = new MatchingEngine(SYMBOLS, 2, dir, false)) {
            restarted.start();
            for (int i = 0; i < SYMBOLS.size(); i++) {
                assertThat(restarted.depth(SYMBOLS.get(i), 1000).get()).isEqualTo(before.get(i));
            }
            assertThat(restarted.highestOrderId()).isEqualTo(flow.stream()
                    .filter(c -> c instanceof Command.NewOrder).count());
        }
    }

    @Test
    void replayingAJournalTwiceGivesIdenticalTrades() {
        Collector live = new Collector();
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 3, dir, false)) {
            engine.addListener(live);
            engine.start();
            runAll(engine, randomFlow(7, 10_000));
        }
        assertThat(live.trades).isNotEmpty();

        for (int run = 0; run < 2; run++) {
            Collector replayed = new Collector();
            try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 1 + run, dir, false)) {
                engine.addListener(replayed);
                engine.start();
            }
            for (String s : SYMBOLS) {
                assertThat(replayed.tradesFor(s)).isEqualTo(live.tradesFor(s));
            }
        }
    }

    @Test
    void tornLastLineIsDroppedOnRecovery() throws IOException {
        try (MatchingEngine engine = new MatchingEngine(List.of("INFY"), 1, dir, false)) {
            engine.start();
            engine.submit(new Command.NewOrder(1, "a", "o1", "INFY", Side.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 10_000, 10)).join();
        }
        Path journal = dir.resolve("INFY-000000000001.journal");
        Files.writeString(journal, "2,N,2,b,o2,INF", StandardOpenOption.APPEND);

        try (MatchingEngine engine = new MatchingEngine(List.of("INFY"), 1, dir, false)) {
            engine.start();
            assertThat(engine.depth("INFY", 5).join().bids()).hasSize(1);
            engine.submit(new Command.CancelOrder(1, "INFY")).join();
        }
        assertThat(Files.readAllLines(journal)).containsExactly(
                "1,N,1,a,o1,INFY,BUY,LIMIT,GTC,10000,10",
                "2,C,1,INFY");
    }

    @Test
    void manyThreadsSubmittingAtOnceLoseNothing() throws Exception {
        Collector out = new Collector();
        int threads = 8;
        int perThread = 5_000;
        AtomicLong ids = new AtomicLong(1);
        Set<Long> acked = ConcurrentHashMap.newKeySet();

        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 2, dir, false)) {
            engine.addListener(out);
            engine.start();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<?>> all = Collections.synchronizedList(new ArrayList<>());
            for (int t = 0; t < threads; t++) {
                int seed = t;
                pool.submit(() -> {
                    Random random = new Random(seed);
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        long id = ids.getAndIncrement();
                        var cmd = new Command.NewOrder(id, "t" + seed, "o" + id, SYMBOLS.get(random.nextInt(4)),
                                random.nextBoolean() ? Side.BUY : Side.SELL, OrderType.LIMIT, TimeInForce.GTC,
                                10_000 + random.nextInt(20) * 5, 1 + random.nextInt(50));
                        all.add(engine.submit(cmd).thenAccept(reports -> {
                            assertThat(reports.get(0).orderId()).isEqualTo(id);
                            assertThat(acked.add(id)).isTrue();
                        }));
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        }

        int total = threads * perThread;
        assertThat(acked).hasSize(total);
        long accepted = out.reports.stream().filter(r -> r.status() == OrderStatus.NEW).count();
        assertThat(accepted).isEqualTo(total);
        assertThat(out.reports.stream().filter(r -> r.status() == OrderStatus.NEW).map(ExecutionReport::orderId)
                .distinct().count()).isEqualTo(total);
    }

    @Test
    void unknownSymbolIsRefused() {
        try (MatchingEngine engine = MatchingEngine.inMemory(SYMBOLS, 1)) {
            engine.start();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.depth("NOPE", 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
