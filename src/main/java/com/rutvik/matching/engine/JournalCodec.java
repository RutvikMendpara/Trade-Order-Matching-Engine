package com.rutvik.matching.engine;

import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;

/**
 * One command per line, comma separated. Text instead of binary because you can
 * just open the file and read it, which has been worth it while debugging.
 * Encoding writes straight into a reused byte buffer, so it's still garbage free.
 * Client ids are validated at the gateway so they never contain commas.
 */
final class JournalCodec {

    record Entry(long sequence, Command command) {
    }

    private JournalCodec() {
    }

    static void encode(long seq, Command command, LineBuffer out) {
        out.put(seq).put(',');
        switch (command) {
            case Command.NewOrder n -> out.put('N').put(',').put(n.orderId()).put(',').put(n.clientId()).put(',')
                    .put(n.clientOrderId()).put(',').put(n.symbol()).put(',').put(n.side().name()).put(',')
                    .put(n.type().name()).put(',').put(n.timeInForce().name()).put(',').put(n.price()).put(',')
                    .put(n.quantity());
            case Command.CancelOrder c -> out.put('C').put(',').put(c.orderId()).put(',').put(c.symbol());
            case Command.AmendOrder a -> out.put('A').put(',').put(a.orderId()).put(',').put(a.symbol()).put(',')
                    .put(a.quantity()).put(',').put(a.price());
            case Command.EndOfDay e -> out.put('E').put(',').put(e.symbol());
        }
        out.put('\n');
    }

    static String encode(long seq, Command command) {
        LineBuffer line = new LineBuffer(128);
        encode(seq, command, line);
        return line.toString().stripTrailing();
    }

    static Entry decode(String line) {
        String[] f = line.split(",");
        long seq = Long.parseLong(f[0]);
        Command command = switch (f[1]) {
            case "N" -> {
                expect(f, 11, line);
                yield new Command.NewOrder(Long.parseLong(f[2]), f[3], f[4], f[5], Side.valueOf(f[6]),
                        OrderType.valueOf(f[7]), TimeInForce.valueOf(f[8]), Long.parseLong(f[9]), Long.parseLong(f[10]));
            }
            case "C" -> {
                expect(f, 4, line);
                yield new Command.CancelOrder(Long.parseLong(f[2]), f[3]);
            }
            case "A" -> {
                expect(f, 6, line);
                yield new Command.AmendOrder(Long.parseLong(f[2]), f[3], Long.parseLong(f[4]), Long.parseLong(f[5]));
            }
            case "E" -> {
                expect(f, 3, line);
                yield new Command.EndOfDay(f[2]);
            }
            default -> throw new IllegalArgumentException("bad journal line: " + line);
        };
        return new Entry(seq, command);
    }

    private static void expect(String[] fields, int count, String line) {
        if (fields.length != count) {
            throw new IllegalArgumentException("bad journal line: " + line);
        }
    }
}
