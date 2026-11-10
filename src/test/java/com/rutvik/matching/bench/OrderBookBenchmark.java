package com.rutvik.matching.bench;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.Order;
import com.rutvik.matching.book.OrderBook;
import com.rutvik.matching.book.Side;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Book with 10k orders per side over 100 levels. Each benchmark leaves the book
 * the same size it found it so the numbers don't drift.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class OrderBookBenchmark {

    private static final long MID = 100_000;

    private OrderBook book;
    private long nextId;

    @Setup
    public void setup() {
        book = new OrderBook("BENCH", BookListener.NONE);
        nextId = 1;
        for (int i = 0; i < 10_000; i++) {
            long offset = 5 * (1 + i % 100);
            book.add(Order.limit(nextId++, "maker", Side.BUY, MID - offset, 100));
            book.add(Order.limit(nextId++, "maker", Side.SELL, MID + offset, 100));
        }
    }

    @Benchmark
    public void addThenCancel() {
        long id = nextId++;
        book.add(Order.limit(id, "taker", Side.BUY, MID - 5 * (1 + id % 100), 100));
        book.cancel(id);
    }

    @Benchmark
    public void matchOneThenRefill() {
        // take out the front order at the best ask, then put an identical one at the back
        book.add(Order.limit(nextId++, "taker", Side.BUY, MID + 5, 100));
        book.add(Order.limit(nextId++, "maker", Side.SELL, MID + 5, 100));
    }

    @Benchmark
    public void sweepFiveLevelsThenRefill() {
        long qty = 100 * 100 * 5; // all of the top five ask levels
        book.add(Order.limit(nextId++, "taker", Side.BUY, MID + 25, qty));
        for (int level = 1; level <= 5; level++) {
            for (int i = 0; i < 100; i++) {
                book.add(Order.limit(nextId++, "maker", Side.SELL, MID + 5 * level, 100));
            }
        }
    }
}
