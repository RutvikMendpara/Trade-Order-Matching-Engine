package com.rutvik.matching.engine;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.OrderBook;
import com.rutvik.matching.book.SnapshotCodec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything on disk for one symbol:
 *
 *   INFY-000000000001.journal    commands 1..n
 *   INFY-000001000000.snapshot   book state after command 1,000,000
 *   INFY-000001000001.journal    commands from 1,000,001
 *
 * Recovery = newest snapshot that passes its checksum + the journal after it.
 * We keep the two newest snapshots (and the journal they need) in case the
 * latest one turns out to be broken.
 */
final class SymbolLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SymbolLog.class);
    private static final int KEEP_SNAPSHOTS = 2;

    private record SeqFile(long seq, Path path) {
    }

    private final Path dir;
    private final String symbol;
    private final boolean fsync;
    private final Pattern journalName;
    private final Pattern snapshotName;
    private Journal journal;

    SymbolLog(Path dir, String symbol, boolean fsync) {
        this.dir = dir;
        this.symbol = symbol;
        this.fsync = fsync;
        this.journalName = Pattern.compile(Pattern.quote(symbol) + "-(\\d+)\\.journal");
        this.snapshotName = Pattern.compile(Pattern.quote(symbol) + "-(\\d+)\\.snapshot");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<SnapshotCodec.Restored> loadSnapshot(BookListener listener) {
        List<SeqFile> snapshots = list(snapshotName);
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            Path file = snapshots.get(i).path();
            try {
                return Optional.of(readSnapshot(file, listener));
            } catch (IOException | RuntimeException e) {
                log.warn("skipping unreadable snapshot {}: {}", file, e.toString());
            }
        }
        return Optional.empty();
    }

    /** Feeds every journaled command after afterSeq to the consumer, then opens the journal for appending. */
    long replay(long afterSeq, Consumer<JournalCodec.Entry> consumer) {
        List<SeqFile> segments = list(journalName);
        long lastSeq = afterSeq;
        for (int i = 0; i < segments.size(); i++) {
            boolean fullyCovered = i + 1 < segments.size() && segments.get(i + 1).seq() <= afterSeq + 1;
            if (fullyCovered) {
                continue;
            }
            for (JournalCodec.Entry entry : Journal.recover(segments.get(i).path())) {
                if (entry.sequence() > afterSeq) {
                    consumer.accept(entry);
                    lastSeq = entry.sequence();
                }
            }
        }
        Path current = segments.isEmpty() ? segment(lastSeq + 1) : segments.get(segments.size() - 1).path();
        journal = new Journal(current, fsync);
        return lastSeq;
    }

    void append(long seq, Command command) {
        journal.append(seq, command);
    }

    Journal journal() {
        return journal;
    }

    /**
     * Runs on the engine thread, so matching for this shard pauses while it writes.
     * Fine at our sizes; a real system would snapshot from a replica instead.
     */
    void snapshot(OrderBook book, long seq, long highestOrderId) {
        journal.flush();
        Path target = dir.resolve(String.format("%s-%012d.snapshot", symbol, seq));
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        try {
            CRC32 crc = new CRC32();
            try (FileOutputStream file = new FileOutputStream(tmp.toFile());
                 CheckedOutputStream checked = new CheckedOutputStream(new BufferedOutputStream(file, 1 << 16), crc);
                 DataOutputStream out = new DataOutputStream(checked)) {
                SnapshotCodec.write(book, seq, highestOrderId, out);
                out.flush();
                new DataOutputStream(file).writeLong(crc.getValue());
                file.getFD().sync();
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot failed for " + symbol, e);
        }

        journal.close();
        journal = new Journal(segment(seq + 1), fsync);
        cleanup();
    }

    private void cleanup() {
        List<SeqFile> snapshots = list(snapshotName);
        if (snapshots.size() <= KEEP_SNAPSHOTS) {
            return;
        }
        long oldestKept = snapshots.get(snapshots.size() - KEEP_SNAPSHOTS).seq();
        for (SeqFile s : snapshots.subList(0, snapshots.size() - KEEP_SNAPSHOTS)) {
            delete(s.path());
        }
        // a journal segment can go once the segment after it starts at or before oldestKept + 1
        List<SeqFile> segments = list(journalName);
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (segments.get(i + 1).seq() <= oldestKept + 1) {
                delete(segments.get(i).path());
            }
        }
    }

    private static SnapshotCodec.Restored readSnapshot(Path file, BookListener listener) throws IOException {
        long size = Files.size(file);
        CRC32 crc = new CRC32();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            CheckedInputStream checked = new CheckedInputStream(new LimitedInputStream(raw, size - 8), crc);
            SnapshotCodec.Restored restored = SnapshotCodec.read(new DataInputStream(checked), listener);
            if (checked.skip(Long.MAX_VALUE) != 0) {
                throw new IOException("trailing bytes in snapshot");
            }
            long expected = new DataInputStream(raw).readLong();
            if (expected != crc.getValue()) {
                throw new IOException("checksum mismatch");
            }
            return restored;
        }
    }

    private Path segment(long startSeq) {
        return dir.resolve(String.format("%s-%012d.journal", symbol, startSeq));
    }

    private List<SeqFile> list(Pattern pattern) {
        List<SeqFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                Matcher m = pattern.matcher(p.getFileName().toString());
                if (m.matches()) {
                    files.add(new SeqFile(Long.parseLong(m.group(1)), p));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        return files;
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("could not delete {}", path, e);
        }
    }

    @Override
    public void close() {
        if (journal != null) {
            journal.close();
        }
    }

    // stops the CRC stream from reading into the checksum trailer
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                left--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }

        @Override
        public void close() {
            // the outer stream owns the file
        }
    }
}
