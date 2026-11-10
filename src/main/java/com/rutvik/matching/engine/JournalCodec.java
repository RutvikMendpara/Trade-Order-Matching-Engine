package com.rutvik.matching.engine;

import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;

/**
 * One command per line, comma separated. Not the fastest format but you can
 * open the file and read it, which has been worth it while debugging.
 * Client ids are validated at the gateway so they never contain commas.
 */
final class JournalCodec {

    record Entry(long sequence, Command command) {
    }

    private JournalCodec() {
    }

    static String encode(long seq, Command command) {
        return switch (command) {
            case Command.NewOrder n -> String.join(",", Long.toString(seq), "N", Long.toString(n.orderId()),
                    n.clientId(), n.clientOrderId(), n.symbol(), n.side().name(), n.type().name(),
                    n.timeInForce().name(), Long.toString(n.price()), Long.toString(n.quantity()));
            case Command.CancelOrder c -> seq + ",C," + c.orderId() + "," + c.symbol();
            case Command.AmendOrder a -> seq + ",A," + a.orderId() + "," + a.symbol() + "," + a.quantity() + "," + a.price();
            case Command.EndOfDay e -> seq + ",E," + e.symbol();
        };
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
