package com.rutvik.matching.book;

import static com.rutvik.matching.book.Side.BUY;
import static com.rutvik.matching.book.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final Recorder events = new Recorder();
    private final OrderBook book = new OrderBook("INFY", events);

    private void limit(long id, String client, Side side, long price, long qty) {
        book.add(Order.limit(id, client, side, price, qty));
    }

    private void limit(long id, Side side, long price, long qty) {
        limit(id, "client" + id, side, price, qty);
    }

    private void order(long id, Side side, OrderType type, TimeInForce tif, long price, long qty) {
        book.add(new Order(id, "client" + id, "c" + id, side, type, tif, price, qty));
    }

    private List<Long> idsAt(Side side, long price) {
        return book.ordersAt(side, price).stream().map(Order::id).toList();
    }

    @Test
    void workedExampleFromTheSpec() {
        limit(1, SELL, 101, 50);   // A
        limit(2, SELL, 101, 70);   // B
        limit(3, SELL, 102, 100);
        events.clear();

        limit(4, BUY, 102, 150);

        assertThat(events.trades).extracting(Trade::sellOrderId, Trade::price, Trade::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 101L, 50L),
                        org.assertj.core.groups.Tuple.tuple(2L, 101L, 70L),
                        org.assertj.core.groups.Tuple.tuple(3L, 102L, 30L));
        assertThat(events.last(4).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(book.bestAsk()).isEqualTo(102);
        assertThat(book.find(3).leaves()).isEqualTo(70);
        assertThat(book.prices(SELL)).containsExactly(102L);
    }

    @Test
    void buyTakesLowestAskFirst() {
        limit(1, SELL, 105, 10);
        limit(2, SELL, 103, 10);
        limit(3, SELL, 104, 10);

        limit(4, BUY, 110, 10);

        assertThat(events.trades).singleElement().satisfies(t -> {
            assertThat(t.sellOrderId()).isEqualTo(2);
            assertThat(t.price()).isEqualTo(103);
        });
    }

    @Test
    void sellTakesHighestBidFirst() {
        limit(1, BUY, 98, 10);
        limit(2, BUY, 100, 10);
        limit(3, BUY, 99, 10);

        limit(4, SELL, 90, 25);

        assertThat(events.trades).extracting(Trade::buyOrderId).containsExactly(2L, 3L, 1L);
        assertThat(events.trades).extracting(Trade::price).containsExactly(100L, 99L, 98L);
    }

    @Test
    void tradesHappenAtTheRestingPrice() {
        limit(1, BUY, 100, 10);
        limit(2, SELL, 95, 10);

        assertThat(events.trades).singleElement().extracting(Trade::price).isEqualTo(100L);
        assertThat(events.trades.get(0).aggressor()).isEqualTo(SELL);
    }

    @Test
    void sameLevelFillsOldestFirst() {
        limit(1, BUY, 100, 10);
        limit(2, BUY, 100, 10);
        limit(3, BUY, 100, 10);

        limit(4, SELL, 100, 15);

        assertThat(events.trades).extracting(Trade::buyOrderId).containsExactly(1L, 2L);
        assertThat(idsAt(BUY, 100)).containsExactly(2L, 3L);
        assertThat(book.find(2).leaves()).isEqualTo(5);
    }

    @Test
    void nonCrossingLimitJustRests() {
        limit(1, BUY, 99, 10);
        limit(2, SELL, 101, 10);

        assertThat(events.trades).isEmpty();
        assertThat(book.bestBid()).isEqualTo(99);
        assertThat(book.bestAsk()).isEqualTo(101);
        assertThat(events.last(2).status()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void partialFillLeavesRemainderResting() {
        limit(1, SELL, 100, 30);
        limit(2, BUY, 100, 50);

        ExecutionReport buy = events.last(2);
        assertThat(buy.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(buy.filledQuantity()).isEqualTo(30);
        assertThat(buy.leavesQuantity()).isEqualTo(20);
        assertThat(book.bestBid()).isEqualTo(100);
        assertThat(book.bestAsk()).isZero();
    }

    @Test
    void exactFillEmptiesBothSides() {
        limit(1, SELL, 100, 40);
        limit(2, BUY, 100, 40);

        assertThat(events.last(1).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(events.last(2).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(book.orderCount()).isZero();
        assertThat(book.prices(BUY)).isEmpty();
        assertThat(book.prices(SELL)).isEmpty();
    }

    @Test
    void marketOrderSweepsAndNeverRests() {
        limit(1, SELL, 100, 10);
        limit(2, SELL, 101, 10);

        book.add(Order.market(3, "mkt", BUY, 25));

        assertThat(events.trades).extracting(Trade::price).containsExactly(100L, 101L);
        ExecutionReport last = events.last(3);
        assertThat(last.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(last.filledQuantity()).isEqualTo(20);
        assertThat(book.orderCount()).isZero();
    }

    @Test
    void marketOrderOnEmptySideIsCancelled() {
        limit(1, BUY, 100, 10);

        book.add(Order.market(2, "mkt", BUY, 5));

        assertThat(events.trades).isEmpty();
        assertThat(events.last(2).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.orderCount()).isEqualTo(1);
    }

    @Test
    void cancelRemovesRestingOrder() {
        limit(1, BUY, 100, 10);
        limit(2, BUY, 100, 10);

        book.cancel(1);

        assertThat(events.last(1).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(idsAt(BUY, 100)).containsExactly(2L);
        assertThat(book.levelQuantity(BUY, 100)).isEqualTo(10);
    }

    @Test
    void cancellingLastOrderRemovesLevel() {
        limit(1, SELL, 100, 10);
        book.cancel(1);
        assertThat(book.prices(SELL)).isEmpty();
    }

    @Test
    void cancelOfFilledOrderIsRejected() {
        limit(1, SELL, 100, 10);
        limit(2, BUY, 100, 10);
        events.clear();

        book.cancel(1);

        assertThat(events.reports).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(r.reason()).contains("unknown");
        });
    }

    @Test
    void reducingQuantityKeepsQueuePosition() {
        limit(1, BUY, 100, 10);
        limit(2, BUY, 100, 10);

        book.amend(1, 4, 100);

        assertThat(idsAt(BUY, 100)).containsExactly(1L, 2L);
        assertThat(book.find(1).leaves()).isEqualTo(4);
        assertThat(book.levelQuantity(BUY, 100)).isEqualTo(14);
        assertThat(events.last(1).status()).isEqualTo(OrderStatus.REPLACED);
    }

    @Test
    void increasingQuantityLosesPriority() {
        limit(1, BUY, 100, 10);
        limit(2, BUY, 100, 10);

        book.amend(1, 20, 100);

        assertThat(idsAt(BUY, 100)).containsExactly(2L, 1L);
        assertThat(book.levelQuantity(BUY, 100)).isEqualTo(30);
    }

    @Test
    void changingPriceLosesPriorityAndCanTrade() {
        limit(1, SELL, 102, 10);
        limit(2, BUY, 100, 10);
        limit(3, BUY, 101, 10);

        book.amend(2, 10, 102);

        assertThat(events.trades).singleElement().satisfies(t -> {
            assertThat(t.buyOrderId()).isEqualTo(2);
            assertThat(t.price()).isEqualTo(102);
        });
        assertThat(book.prices(BUY)).containsExactly(101L);
    }

    @Test
    void amendAfterPartialFillWorksOnLeaves() {
        limit(1, BUY, 100, 10);
        limit(2, SELL, 100, 4);

        book.amend(1, 2, 100);

        Order order = book.find(1);
        assertThat(order.filled()).isEqualTo(4);
        assertThat(order.leaves()).isEqualTo(2);
        assertThat(order.quantity()).isEqualTo(6);
    }

    @Test
    void iocFillsWhatItCanAndCancelsTheRest() {
        limit(1, SELL, 100, 10);

        order(2, BUY, OrderType.LIMIT, TimeInForce.IOC, 100, 25);

        assertThat(events.trades).hasSize(1);
        assertThat(events.last(2).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(events.last(2).filledQuantity()).isEqualTo(10);
        assertThat(book.bestBid()).isZero();
    }

    @Test
    void fokIsAllOrNothing() {
        limit(1, SELL, 100, 10);
        limit(2, SELL, 101, 10);

        order(3, BUY, OrderType.LIMIT, TimeInForce.FOK, 101, 25);
        assertThat(events.trades).isEmpty();
        assertThat(events.last(3).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.orderCount()).isEqualTo(2);

        order(4, BUY, OrderType.LIMIT, TimeInForce.FOK, 101, 20);
        assertThat(events.trades).hasSize(2);
        assertThat(events.last(4).status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void fokRespectsLimitPrice() {
        limit(1, SELL, 100, 10);
        limit(2, SELL, 105, 100);

        order(3, BUY, OrderType.LIMIT, TimeInForce.FOK, 101, 20);

        assertThat(events.trades).isEmpty();
    }

    @Test
    void selfTradeIsPreventedAndIncomingCancelled() {
        limit(1, "alice", SELL, 100, 10);
        limit(2, "bob", SELL, 100, 10);
        limit(3, "alice", BUY, 100, 15);

        assertThat(events.trades).isEmpty();
        assertThat(events.last(3).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(events.last(3).reason()).contains("self-trade");
        assertThat(idsAt(SELL, 100)).containsExactly(1L, 2L);
    }

    @Test
    void selfTradeStopsAfterTradingAheadOfOwnOrder() {
        limit(1, "bob", SELL, 100, 5);
        limit(2, "alice", SELL, 100, 10);
        limit(3, "alice", BUY, 100, 15);

        assertThat(events.trades).singleElement().extracting(Trade::sellOrderId).isEqualTo(1L);
        assertThat(events.last(3).filledQuantity()).isEqualTo(5);
        assertThat(events.last(3).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void dayOrdersExpireGtcStays() {
        order(1, BUY, OrderType.LIMIT, TimeInForce.DAY, 100, 10);
        order(2, BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 10);

        book.expireDayOrders();

        assertThat(events.last(1).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(idsAt(BUY, 100)).containsExactly(2L);
    }

    @Test
    void rejectsBadOrders() {
        limit(1, BUY, 100, 0);
        limit(2, BUY, 0, 10);
        limit(3, BUY, 100, 10);
        limit(3, BUY, 100, 10);

        assertThat(events.reports).filteredOn(r -> r.status() == OrderStatus.REJECTED).hasSize(3);
        assertThat(book.orderCount()).isEqualTo(1);
    }

    @Test
    void snapshotAggregatesLevels() {
        limit(1, BUY, 100, 10);
        limit(2, BUY, 100, 5);
        limit(3, BUY, 99, 7);
        limit(4, SELL, 101, 3);
        for (int i = 0; i < 10; i++) {
            limit(10 + i, SELL, 110 + i, 1);
        }

        BookSnapshot snap = book.snapshot(5);

        assertThat(snap.bids()).containsExactly(new BookSnapshot.Level(100, 15, 2), new BookSnapshot.Level(99, 7, 1));
        assertThat(snap.asks()).hasSize(5);
        assertThat(snap.bestAsk()).isEqualTo(101);
    }

    @Test
    void reportsComeOutInOrder() {
        limit(1, SELL, 100, 10);
        events.clear();
        limit(2, BUY, 100, 10);

        assertThat(events.reports).extracting(ExecutionReport::orderId, ExecutionReport::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, OrderStatus.NEW),
                        org.assertj.core.groups.Tuple.tuple(2L, OrderStatus.FILLED),
                        org.assertj.core.groups.Tuple.tuple(1L, OrderStatus.FILLED));
    }
}
