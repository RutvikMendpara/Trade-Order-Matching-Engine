package com.rutvik.matching.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Limit order book for a single symbol. Not thread safe - exactly one engine
 * thread owns each book, which is what keeps matching lock free and deterministic.
 */
public final class OrderBook {

    private final String symbol;
    private final BookListener listener;

    final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final LongMap<Order> orders = new LongMap<>(1 << 16);

    long nextTradeId = 1;
    long nextPriority = 1;
    long lastTradePrice;

    public OrderBook(String symbol, BookListener listener) {
        this.symbol = symbol;
        this.listener = listener;
    }

    public void add(Order order) {
        String problem = validate(order);
        if (problem != null) {
            report(order, OrderStatus.REJECTED, 0, 0, problem);
            return;
        }
        report(order, OrderStatus.NEW, 0, 0, null);

        if (order.timeInForce() == TimeInForce.FOK && !canFillCompletely(order)) {
            report(order, OrderStatus.CANCELLED, 0, 0, "FOK could not be fully filled");
            return;
        }
        matchAndRest(order);
    }

    public void cancel(long orderId) {
        Order order = orders.remove(orderId);
        if (order == null) {
            listener.onReport(ExecutionReport.cancelReject(orderId, symbol, "unknown or already closed order"));
            return;
        }
        unlink(order);
        report(order, OrderStatus.CANCELLED, 0, 0, "cancelled by client");
    }

    /**
     * newQuantity is the new open quantity. Shrinking at the same price keeps the
     * order's place in the queue, anything else is effectively cancel + new.
     */
    public void amend(long orderId, long newQuantity, long newPrice) {
        Order order = orders.get(orderId);
        if (order == null) {
            listener.onReport(ExecutionReport.cancelReject(orderId, symbol, "unknown or already closed order"));
            return;
        }
        if (newQuantity <= 0 || newPrice <= 0) {
            listener.onReport(ExecutionReport.cancelReject(orderId, symbol, "invalid amend"));
            return;
        }

        if (newPrice == order.price && newQuantity <= order.leaves()) {
            long reduceBy = order.leaves() - newQuantity;
            order.quantity -= reduceBy;
            order.level.totalQuantity -= reduceBy;
            report(order, OrderStatus.REPLACED, 0, 0, null);
            return;
        }

        orders.remove(orderId);
        unlink(order);
        order.price = newPrice;
        order.quantity = order.filled + newQuantity;
        report(order, OrderStatus.REPLACED, 0, 0, "lost priority");
        matchAndRest(order);
    }

    public void expireDayOrders() {
        List<Order> expiring = new ArrayList<>();
        orders.forEachValue(order -> {
            if (order.timeInForce() == TimeInForce.DAY) {
                expiring.add(order);
            }
        });
        // hash order isn't meaningful, sort so replays give identical output
        expiring.sort(Comparator.comparingLong(Order::id));
        for (Order order : expiring) {
            orders.remove(order.id());
            unlink(order);
            report(order, OrderStatus.CANCELLED, 0, 0, "end of day");
        }
    }

    private void matchAndRest(Order order) {
        boolean selfTrade = match(order);
        if (order.leaves() == 0) {
            return;
        }
        if (selfTrade) {
            report(order, OrderStatus.CANCELLED, 0, 0, "self-trade prevented");
        } else if (order.isMarket()) {
            report(order, OrderStatus.CANCELLED, 0, 0, "no more liquidity for market order");
        } else if (order.timeInForce() == TimeInForce.IOC || order.timeInForce() == TimeInForce.FOK) {
            report(order, OrderStatus.CANCELLED, 0, 0, "IOC remainder cancelled");
        } else {
            rest(order);
        }
    }

    // returns true if we stopped because the next resting order belongs to the same client
    private boolean match(Order incoming) {
        NavigableMap<Long, PriceLevel> opposite = incoming.side() == Side.BUY ? asks : bids;

        while (incoming.leaves() > 0 && !opposite.isEmpty()) {
            PriceLevel level = opposite.firstEntry().getValue();
            if (!crosses(incoming, level.price)) {
                break;
            }

            Order resting = level.head;
            while (resting != null && incoming.leaves() > 0) {
                if (resting.clientId().equals(incoming.clientId())) {
                    return true;
                }
                Order next = resting.next;
                long qty = Math.min(incoming.leaves(), resting.leaves());
                fill(incoming, resting, level, qty);
                if (resting.leaves() == 0) {
                    level.remove(resting);
                    orders.remove(resting.id());
                }
                resting = next;
            }

            if (level.isEmpty()) {
                opposite.remove(level.key);
            }
        }
        return false;
    }

    private void fill(Order incoming, Order resting, PriceLevel level, long qty) {
        long price = level.price; // always trade at the resting order's price
        incoming.filled += qty;
        resting.filled += qty;
        level.totalQuantity -= qty;
        lastTradePrice = price;

        boolean buyIsIncoming = incoming.side() == Side.BUY;
        Order buy = buyIsIncoming ? incoming : resting;
        Order sell = buyIsIncoming ? resting : incoming;
        listener.onTrade(new Trade(nextTradeId++, symbol, price, qty, buy.id(), sell.id(),
                buy.clientId(), sell.clientId(), incoming.side()));

        report(incoming, fillStatus(incoming), price, qty, null);
        report(resting, fillStatus(resting), price, qty, null);
    }

    private boolean canFillCompletely(Order order) {
        NavigableMap<Long, PriceLevel> opposite = order.side() == Side.BUY ? asks : bids;
        long needed = order.leaves();
        for (PriceLevel level : opposite.values()) {
            if (!crosses(order, level.price)) {
                return false;
            }
            for (Order o = level.head; o != null; o = o.next) {
                if (o.clientId().equals(order.clientId())) {
                    return false;
                }
                needed -= o.leaves();
                if (needed <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rest(Order order) {
        NavigableMap<Long, PriceLevel> side = order.side() == Side.BUY ? bids : asks;
        PriceLevel level = side.get(order.price);
        if (level == null) {
            level = new PriceLevel(order.price);
            side.put(level.key, level);
        }
        order.priority = nextPriority++;
        level.append(order);
        orders.put(order.id(), order);
    }

    // snapshot restore: orders arrive in queue order, so appending rebuilds FIFO exactly
    void restore(Order order) {
        NavigableMap<Long, PriceLevel> side = order.side() == Side.BUY ? bids : asks;
        PriceLevel level = side.get(order.price);
        if (level == null) {
            level = new PriceLevel(order.price);
            side.put(level.key, level);
        }
        level.append(order);
        orders.put(order.id(), order);
    }

    // lets downstream read models learn about orders that came back from a snapshot
    void announceResting() {
        for (NavigableMap<Long, PriceLevel> side : List.of(bids, asks)) {
            for (PriceLevel level : side.values()) {
                for (Order o = level.head; o != null; o = o.next) {
                    OrderStatus status = o.filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW;
                    report(o, status, 0, 0, "restored from snapshot");
                }
            }
        }
    }

    private void unlink(Order order) {
        PriceLevel level = order.level;
        level.remove(order);
        if (level.isEmpty()) {
            (order.side() == Side.BUY ? bids : asks).remove(level.key);
        }
    }

    private String validate(Order order) {
        if (order.quantity() <= 0) {
            return "quantity must be positive";
        }
        if (order.type() == OrderType.LIMIT && order.price() <= 0) {
            return "limit price must be positive";
        }
        if (orders.containsKey(order.id())) {
            return "duplicate order id";
        }
        return null;
    }

    private static boolean crosses(Order incoming, long levelPrice) {
        if (incoming.isMarket()) {
            return true;
        }
        return incoming.side() == Side.BUY ? levelPrice <= incoming.price() : levelPrice >= incoming.price();
    }

    private static OrderStatus fillStatus(Order order) {
        return order.leaves() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    private void report(Order o, OrderStatus status, long lastPrice, long lastQty, String reason) {
        long leaves = status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED ? 0 : o.leaves();
        listener.onReport(new ExecutionReport(o.id(), o.clientId(), o.clientOrderId(), symbol, o.side(), o.type(),
                status, o.price(), o.quantity(), o.filled(), leaves, lastPrice, lastQty, reason));
    }

    // ---- read side ----

    public String symbol() {
        return symbol;
    }

    public long bestBid() {
        return bids.isEmpty() ? 0 : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? 0 : asks.firstKey();
    }

    public long lastTradePrice() {
        return lastTradePrice;
    }

    public int orderCount() {
        return orders.size();
    }

    public Order find(long orderId) {
        return orders.get(orderId);
    }

    public BookSnapshot snapshot(int depth) {
        return new BookSnapshot(symbol, levels(bids, depth), levels(asks, depth), lastTradePrice);
    }

    /** Orders resting at one price, front of the queue first. Mostly for tests. */
    public List<Order> ordersAt(Side side, long price) {
        PriceLevel level = (side == Side.BUY ? bids : asks).get(price);
        if (level == null) {
            return Collections.emptyList();
        }
        List<Order> result = new ArrayList<>(level.orderCount);
        for (Order o = level.head; o != null; o = o.next) {
            result.add(o);
        }
        return result;
    }

    public List<Long> prices(Side side) {
        return new ArrayList<>((side == Side.BUY ? bids : asks).keySet());
    }

    long levelQuantity(Side side, long price) {
        PriceLevel level = (side == Side.BUY ? bids : asks).get(price);
        return level == null ? 0 : level.totalQuantity;
    }

    private static List<BookSnapshot.Level> levels(NavigableMap<Long, PriceLevel> side, int depth) {
        List<BookSnapshot.Level> result = new ArrayList<>(Math.min(depth, side.size()));
        for (PriceLevel level : side.values()) {
            if (result.size() == depth) {
                break;
            }
            result.add(new BookSnapshot.Level(level.price, level.totalQuantity, level.orderCount));
        }
        return result;
    }
}
