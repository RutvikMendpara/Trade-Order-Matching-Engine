package com.rutvik.matching.bench;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.Order;
import com.rutvik.matching.book.OrderBook;
import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;
import com.rutvik.matching.engine.Command;
import com.rutvik.matching.engine.MatchingEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Two runs:
 *  1. the order book on its own, single thread, latency of every call
 *  2. the whole engine with journaling on, several producer threads, latency from
 *     submit() until the ack comes back (which includes the queue hop and the flush)
 */
public class LoadGenerator {

    static final String[] SYMBOLS = {"INFY", "TCS", "RELIANCE", "HDFCBANK"};

    public static void main(String[] args) throws Exception {
        int bookOrders = args.length > 0 ? Integer.parseInt(args[0]) : 5_000_000;
        int engineOrders = args.length > 1 ? Integer.parseInt(args[1]) : 2_000_000;

        System.out.printf("JDK %s on %s (%d cores)%n%n", Runtime.version(), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        bookOnly(bookOrders / 5, false);
        bookOnly(bookOrders, true);
        wholeEngine(engineOrders / 5, 256, false);
        wholeEngine(engineOrders, 256, true);
        wholeEngine(engineOrders / 4, 1, true);
    }

    // mix: ~60% limit orders around the mid, ~30% cancels, ~5% amends, ~5% market orders
    static List<Command> flow(int count, long seed, String symbol, long firstId) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Command> out = new ArrayList<>(count);
        long id = firstId;
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            if (roll < 30 && id > firstId + 1000) {
                out.add(new Command.CancelOrder(firstId + random.nextLong(id - firstId), symbol));
            } else if (roll < 35 && id > firstId + 1000) {
                out.add(new Command.AmendOrder(id - 1 - random.nextInt(1000), symbol,
                        1 + random.nextInt(500), 100_000 + 5L * (random.nextInt(41) - 20)));
            } else {
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                boolean market = roll >= 95;
                // passive side a bit wider than aggressive so the book builds up some depth
                long offset = 5L * random.nextInt(-5, 20);
                long price = side == Side.BUY ? 100_000 - offset : 100_000 + offset;
                out.add(new Command.NewOrder(id, "c" + random.nextInt(50), "o" + id, symbol, side,
                        market ? OrderType.MARKET : OrderType.LIMIT, market ? TimeInForce.IOC : TimeInForce.GTC,
                        market ? 0 : price, 1 + random.nextInt(500)));
                id++;
            }
        }
        return out;
    }

    static void bookOnly(int count, boolean print) {
        List<Command> commands = flow(count, 1, "INFY", 1);
        Order[] orders = new Order[count];
        for (int i = 0; i < count; i++) {
            if (commands.get(i) instanceof Command.NewOrder n) {
                orders[i] = new Order(n.orderId(), n.clientId(), n.clientOrderId(), n.side(), n.type(),
                        n.timeInForce(), n.price(), n.quantity());
            }
        }

        Counter counter = new Counter();
        OrderBook book = new OrderBook("INFY", counter);
        Histogram latency = new Histogram(10_000_000_000L, 3);

        System.gc();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long t0 = System.nanoTime();
            switch (commands.get(i)) {
                case Command.NewOrder ignored -> book.add(orders[i]);
                case Command.CancelOrder c -> book.cancel(c.orderId());
                case Command.AmendOrder a -> book.amend(a.orderId(), a.quantity(), a.price());
                case Command.EndOfDay ignored -> book.expireDayOrders();
            }
            latency.recordValue(System.nanoTime() - t0);
        }
        long elapsed = System.nanoTime() - start;

        if (print) {
            System.out.printf("order book only: %,d commands, %,d trades, %,d resting at the end%n",
                    count, counter.trades, book.orderCount());
            print(count, elapsed, latency);
        }
    }

    // window = max commands in flight per producer. 256 shows throughput, 1 shows round trip latency
    static void wholeEngine(int count, int window, boolean print) throws Exception {
        Path dir = Files.createTempDirectory("journal");
        int producers = SYMBOLS.length;
        int perProducer = count / producers;

        List<List<Command>> flows = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            flows.add(flow(perProducer, 100 + p, SYMBOLS[p], 1 + (long) p * perProducer * 2));
        }

        Recorder recorder = new Recorder(3);
        long elapsed;
        try (MatchingEngine engine = new MatchingEngine(List.of(SYMBOLS), 2, dir, false)) {
            engine.start();
            CountDownLatch done = new CountDownLatch(producers);
            List<Thread> threads = new ArrayList<>();

            long start = System.nanoTime();
            for (int p = 0; p < producers; p++) {
                List<Command> commands = flows.get(p);
                Thread t = new Thread(() -> {
                    Semaphore inFlight = new Semaphore(window);
                    for (Command c : commands) {
                        inFlight.acquireUninterruptibly();
                        long t0 = System.nanoTime();
                        engine.submit(c).whenComplete((r, e) -> {
                            recorder.recordValue(System.nanoTime() - t0);
                            inFlight.release();
                        });
                    }
                    inFlight.acquireUninterruptibly(window);
                    done.countDown();
                }, "producer-" + p);
                threads.add(t);
                t.start();
            }
            done.await();
            elapsed = System.nanoTime() - start;
        }

        long journalBytes;
        try (var files = Files.list(dir)) {
            journalBytes = files.mapToLong(f -> f.toFile().length()).sum();
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }

        if (print) {
            System.out.printf("%nfull engine: %,d commands, %d symbols on 2 shards, %d producers, %d in flight each, journal %,d MB%n",
                    perProducer * producers, SYMBOLS.length, producers, window, journalBytes >> 20);
            print(perProducer * producers, elapsed, recorder.getIntervalHistogram());
        }
    }

    static void print(int count, long elapsedNanos, Histogram h) {
        System.out.printf("  throughput  %,.0f /sec%n", count / (elapsedNanos / 1e9));
        System.out.printf("  p50  %8.2f us%n", h.getValueAtPercentile(50) / 1000.0);
        System.out.printf("  p99  %8.2f us%n", h.getValueAtPercentile(99) / 1000.0);
        System.out.printf("  p99.9 %7.2f us%n", h.getValueAtPercentile(99.9) / 1000.0);
        System.out.printf("  max  %8.2f us%n", h.getMaxValue() / 1000.0);
    }

    static class Counter implements BookListener {
        long trades;
        long reports;

        @Override
        public void onReport(com.rutvik.matching.book.ExecutionReport report) {
            reports++;
        }

        @Override
        public void onTrade(com.rutvik.matching.book.Trade trade) {
            trades++;
        }
    }
}
