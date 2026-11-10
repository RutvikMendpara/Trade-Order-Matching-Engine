package com.rutvik.matching.engine;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append only log of inbound commands for one symbol. The engine writes here
 * before it touches the book, and only acks clients after a flush.
 */
public final class Journal implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);

    private final Path file;
    private final boolean fsync;
    private final FileOutputStream out;
    private final LineBuffer buffer = new LineBuffer(1 << 16);

    public Journal(Path file, boolean fsync) {
        this.file = file;
        this.fsync = fsync;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            this.out = new FileOutputStream(file.toFile(), true);
        } catch (IOException e) {
            throw new UncheckedIOException("could not open journal " + file, e);
        }
    }

    public void append(long seq, Command command) {
        if (buffer.length > (1 << 16) - 512) {
            write();
        }
        JournalCodec.encode(seq, command, buffer);
    }

    public void flush() {
        if (buffer.length == 0) {
            return;
        }
        write();
        if (fsync) {
            try {
                out.getFD().sync();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void write() {
        try {
            out.write(buffer.bytes, 0, buffer.length);
            buffer.clear();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads everything back. Anything after the last newline is a write that never
     * finished (we crashed mid flush), so it was never acked - drop it and truncate.
     */
    static List<JournalCodec.Entry> recover(Path file) {
        List<JournalCodec.Entry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int end = content.lastIndexOf('\n') + 1;
            if (end < content.length()) {
                log.warn("dropping torn tail of {}: '{}'", file, content.substring(end));
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(content.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
                }
            }
            for (String line : content.substring(0, end).split("\n")) {
                if (!line.isBlank()) {
                    entries.add(JournalCodec.decode(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        try {
            flush();
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
