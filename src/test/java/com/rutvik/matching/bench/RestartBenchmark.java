package com.rutvik.matching.bench;

import com.rutvik.matching.engine.Command;
import com.rutvik.matching.engine.MatchingEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Restart time with and without snapshots, same 5M command history both times.
 */
public class RestartBenchmark {

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 5_000_000;
        List<Command> flow = LoadGenerator.flow(count, 1, "INFY", 1);

        run("journal only", flow, 0);
        run("snapshot every 1.5M", flow, 1_500_000);
    }

    static void run(String name, List<Command> flow, long snapshotEvery) throws Exception {
        Path dir = Files.createTempDirectory("restart");
        var options = MatchingEngine.Options.journaled(dir).withSnapshotEvery(snapshotEvery);
        int resting;

        try (MatchingEngine engine = new MatchingEngine(List.of("INFY"), 1, options)) {
            engine.start();
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (Command c : flow) {
                pending.add(engine.submit(c));
                if (pending.size() == 10_000) {
                    pending.forEach(CompletableFuture::join);
                    pending.clear();
                }
            }
            pending.forEach(CompletableFuture::join);
            var book = engine.depth("INFY", Integer.MAX_VALUE).join();
            resting = book.bids().stream().mapToInt(l -> l.orders()).sum()
                    + book.asks().stream().mapToInt(l -> l.orders()).sum();
        }
        long onDisk = size(dir);

        // best of 3 so one unlucky GC doesn't decide it
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            System.gc();
            long start = System.nanoTime();
            try (MatchingEngine engine = new MatchingEngine(List.of("INFY"), 1, options)) {
                engine.start();
                engine.depth("INFY", 1).join();
            }
            best = Math.min(best, System.nanoTime() - start);
        }

        System.out.printf("%-22s %,d commands, %,d resting, %,d MB on disk, restart %,d ms%n",
                name, flow.size(), resting, onDisk >> 20, best / 1_000_000);
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static long size(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
