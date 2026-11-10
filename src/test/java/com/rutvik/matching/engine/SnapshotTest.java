package com.rutvik.matching.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderStatus;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotTest {

    private static final List<String> SYMBOLS = List.of("INFY", "TCS", "RELIANCE", "HDFCBANK");

    @TempDir
    Path dir;

    @TempDir
    Path journalOnlyDir;

    private static void runAll(MatchingEngine engine, List<Command> commands) {
        List<CompletableFuture<List<ExecutionReport>>> futures = new ArrayList<>();
        for (Command c : commands) {
            futures.add(engine.submit(c));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static List<BookSnapshot> books(MatchingEngine engine) {
        return SYMBOLS.stream().map(s -> engine.depth(s, Integer.MAX_VALUE).join()).toList();
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("INFY")).sorted().toList();
        }
    }

    @Test
    void restoredBooksBehaveExactlyLikeNeverRestartedOnes() {
        List<Command> flow = EngineTest.randomFlow(11, 30_000);
        List<Command> before = flow.subList(0, 15_000);
        List<Command> middle = flow.subList(15_000, 20_000);
        List<Command> after = flow.subList(20_000, 30_000);

        // engine A: snapshot half way, restart, keep going
        try (MatchingEngine a = new MatchingEngine(SYMBOLS, 2, MatchingEngine.Options.journaled(dir))) {
            a.start();
            runAll(a, before);
            a.saveSnapshots();
            runAll(a, middle);
        }
        EngineTest.Collector restoredTrades = new EngineTest.Collector();
        List<BookSnapshot> restoredBooks;
        try (MatchingEngine a = new MatchingEngine(SYMBOLS, 2, MatchingEngine.Options.journaled(dir))) {
            a.start();
            a.addListener(restoredTrades);
            runAll(a, after);
            restoredBooks = books(a);
        }

        // engine B: same commands, never restarted
        EngineTest.Collector plainTrades = new EngineTest.Collector();
        List<BookSnapshot> plainBooks;
        try (MatchingEngine b = MatchingEngine.inMemory(SYMBOLS, 2)) {
            b.start();
            runAll(b, flow.subList(0, 20_000));
            b.addListener(plainTrades);
            runAll(b, after);
            plainBooks = books(b);
        }

        assertThat(restoredBooks).isEqualTo(plainBooks);
        assertThat(restoredTrades.trades).isNotEmpty();
        for (String s : SYMBOLS) {
            assertThat(restoredTrades.tradesFor(s)).isEqualTo(plainTrades.tradesFor(s));
        }
    }

    @Test
    void restartUsesSnapshotPlusTailOfJournal() throws IOException {
        List<Command> flow = EngineTest.randomFlow(3, 20_000);
        List<BookSnapshot> expected;
        var options = MatchingEngine.Options.journaled(dir).withSnapshotEvery(1_000);
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 2, options)) {
            engine.start();
            runAll(engine, flow);
            expected = books(engine);
        }

        // old snapshots and the journal segments only they needed got cleaned up
        List<String> infy = files();
        assertThat(infy.stream().filter(n -> n.endsWith(".snapshot"))).hasSize(2);
        assertThat(infy.stream().filter(n -> n.endsWith(".journal")).count()).isLessThanOrEqualTo(3);

        EngineTest.Collector recovered = new EngineTest.Collector();
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 3, options)) {
            engine.addListener(recovered);
            engine.start();
            assertThat(books(engine)).isEqualTo(expected);
        }
        // resting orders are announced on restore so the gateway can rebuild its view
        assertThat(recovered.reports).anyMatch(r -> "restored from snapshot".equals(r.reason())
                && (r.status() == OrderStatus.NEW || r.status() == OrderStatus.PARTIALLY_FILLED));
    }

    @Test
    void corruptSnapshotFallsBackToThePreviousOne() throws IOException {
        List<Command> flow = EngineTest.randomFlow(5, 12_000);
        List<BookSnapshot> expected;
        var options = MatchingEngine.Options.journaled(dir).withSnapshotEvery(1_000);
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 1, options)) {
            engine.start();
            runAll(engine, flow);
            expected = books(engine);
        }

        String newest = files().stream().filter(n -> n.endsWith(".snapshot")).reduce((x, y) -> y).orElseThrow();
        try (RandomAccessFile f = new RandomAccessFile(dir.resolve(newest).toFile(), "rw")) {
            f.seek(f.length() / 2);
            int b = f.read();
            f.seek(f.length() / 2);
            f.write(b ^ 0xFF);
        }

        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 1, options)) {
            engine.start();
            assertThat(books(engine)).isEqualTo(expected);
        }
    }

    @Test
    void snapshotWithNothingNewIsSkipped() throws IOException {
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 1, MatchingEngine.Options.journaled(dir))) {
            engine.start();
            engine.saveSnapshots();
        }
        assertThat(files()).noneMatch(n -> n.endsWith(".snapshot"));
    }
}
