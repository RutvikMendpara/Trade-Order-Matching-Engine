package com.rutvik.matching.book;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.NavigableMap;

/**
 * Binary dump of a whole book: counters plus every resting order in queue order.
 * Restoring it gives a book that behaves exactly like the one that was saved.
 */
public final class SnapshotCodec {

    private static final int MAGIC = 0x4F425353;
    private static final int VERSION = 1;

    public record Restored(OrderBook book, long sequence, long highestOrderId) {
    }

    private SnapshotCodec() {
    }

    public static void write(OrderBook book, long sequence, long highestOrderId, DataOutputStream out)
            throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(book.symbol());
        out.writeLong(sequence);
        out.writeLong(highestOrderId);
        out.writeLong(book.nextTradeId);
        out.writeLong(book.nextPriority);
        out.writeLong(book.lastTradePrice);
        out.writeInt(book.orderCount());
        writeSide(book.bids, out);
        writeSide(book.asks, out);
    }

    private static void writeSide(NavigableMap<Long, PriceLevel> side, DataOutputStream out) throws IOException {
        for (PriceLevel level : side.values()) {
            for (Order o = level.head; o != null; o = o.next) {
                out.writeLong(o.id());
                out.writeUTF(o.clientId());
                out.writeUTF(o.clientOrderId());
                out.writeByte(o.side().ordinal());
                out.writeByte(o.type().ordinal());
                out.writeByte(o.timeInForce().ordinal());
                out.writeLong(o.price);
                out.writeLong(o.quantity);
                out.writeLong(o.filled);
                out.writeLong(o.priority);
            }
        }
    }

    public static Restored read(DataInputStream in, BookListener listener) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("not a snapshot file, or a different version");
        }
        OrderBook book = new OrderBook(in.readUTF(), listener);
        long sequence = in.readLong();
        long highestOrderId = in.readLong();
        book.nextTradeId = in.readLong();
        book.nextPriority = in.readLong();
        book.lastTradePrice = in.readLong();

        Side[] sides = Side.values();
        OrderType[] types = OrderType.values();
        TimeInForce[] tifs = TimeInForce.values();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            long id = in.readLong();
            String clientId = in.readUTF();
            String clientOrderId = in.readUTF();
            Order o = new Order(id, clientId, clientOrderId, sides[in.readByte()], types[in.readByte()],
                    tifs[in.readByte()], in.readLong(), in.readLong());
            o.filled = in.readLong();
            o.priority = in.readLong();
            book.restore(o);
        }
        book.announceResting();
        return new Restored(book, sequence, highestOrderId);
    }
}
