package com.rutvik.matching.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Throws random sequences of new/cancel/amend at a book and checks the things
 * that must always hold, after every single step.
 */
class OrderBookPropertiesTest {

    sealed interface Op {
    }

    record Add(Side side, OrderType type, TimeInForce tif, long price, long qty, int client) implements Op {
    }

    record Cancel(int pick) implements Op {
    }

    record Amend(int pick, long qty, long price) implements Op {
    }

    @Property(tries = 1000)
    void invariantsHold(@ForAll("ops") @Size(min = 1, max = 300) List<Op> ops) {
        Tracker tracker = new Tracker();
        OrderBook book = new OrderBook("TEST", tracker);
        long nextId = 1;

        for (Op op : ops) {
            switch (op) {
                case Add a -> {
                    long price = a.type() == OrderType.MARKET ? 0 : a.price();
                    book.add(new Order(nextId++, "c" + a.client(), "x", a.side(), a.type(), a.tif(), price, a.qty()));
                }
                case Cancel c -> book.cancel(pickId(c.pick(), nextId));
                case Amend m -> book.amend(pickId(m.pick(), nextId), m.qty(), m.price());
            }
            check(book, tracker);
        }
    }

    private static long pickId(int pick, long nextId) {
        return nextId == 1 ? 1 : 1 + (pick % (nextId - 1));
    }

    private void check(OrderBook book, Tracker t) {
        // never crossed
        if (book.bestBid() > 0 && book.bestAsk() > 0) {
            assertThat(book.bestBid()).isLessThan(book.bestAsk());
        }

        long resting = 0;
        for (Side side : Side.values()) {
            for (long price : book.prices(side)) {
                List<Order> queue = book.ordersAt(side, price);
                assertThat(queue).isNotEmpty();
                long levelTotal = 0;
                long lastPriority = 0;
                for (Order o : queue) {
                    // FIFO: queue order == arrival order into the level
                    assertThat(o.priority()).isGreaterThan(lastPriority);
                    lastPriority = o.priority();
                    assertThat(o.leaves()).isPositive();
                    assertThat(o.price()).isEqualTo(price);
                    assertThat(o.side()).isEqualTo(side);
                    levelTotal += o.leaves();
                }
                assertThat(book.levelQuantity(side, price)).isEqualTo(levelTotal);
                resting += levelTotal;
            }
        }

        // nothing created or lost: submitted = filled + resting + cancelled
        assertThat(t.submitted).isEqualTo(t.filled + resting + t.cancelled);
        assertThat(t.buyFilled).isEqualTo(t.sellFilled);
        assertThat(t.tradedQty * 2).isEqualTo(t.filled);
    }

    static class Tracker implements BookListener {
        final Map<Long, Long> leaves = new HashMap<>();
        long submitted, filled, cancelled, buyFilled, sellFilled, tradedQty;

        @Override
        public void onReport(ExecutionReport r) {
            switch (r.status()) {
                case NEW -> {
                    submitted += r.quantity();
                    leaves.put(r.orderId(), r.quantity());
                }
                case PARTIALLY_FILLED, FILLED -> {
                    filled += r.lastQuantity();
                    if (r.side() == Side.BUY) buyFilled += r.lastQuantity();
                    else sellFilled += r.lastQuantity();
                    leaves.put(r.orderId(), r.leavesQuantity());
                }
                case REPLACED -> {
                    // count the old open qty as cancelled and the new one as freshly submitted
                    cancelled += leaves.get(r.orderId());
                    submitted += r.leavesQuantity();
                    leaves.put(r.orderId(), r.leavesQuantity());
                }
                case CANCELLED -> cancelled += leaves.put(r.orderId(), 0L);
                case REJECTED -> {
                }
            }
        }

        @Override
        public void onTrade(Trade trade) {
            tradedQty += trade.quantity();
            assertThat(trade.buyClientId()).isNotEqualTo(trade.sellClientId());
        }
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Long> prices = Arbitraries.longs().between(95, 105);
        Arbitrary<Long> qtys = Arbitraries.longs().between(1, 50);

        Arbitrary<Op> add = Combinators.combine(
                Arbitraries.of(Side.class),
                Arbitraries.of(OrderType.class).edgeCases(c -> c.includeOnly(OrderType.LIMIT)),
                Arbitraries.of(TimeInForce.GTC, TimeInForce.GTC, TimeInForce.DAY, TimeInForce.IOC, TimeInForce.FOK),
                prices, qtys, Arbitraries.integers().between(0, 4))
                .as((side, type, tif, price, qty, client) -> (Op) new Add(side, type, tif, price, qty, client));
        Arbitrary<Op> cancel = Arbitraries.integers().between(0, 1000).map(Cancel::new);
        Arbitrary<Op> amend = Combinators.combine(Arbitraries.integers().between(0, 1000), qtys, prices)
                .as(Amend::new);

        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(6, add),
                        net.jqwik.api.Tuple.of(2, cancel),
                        net.jqwik.api.Tuple.of(2, amend))
                .list();
    }
}
