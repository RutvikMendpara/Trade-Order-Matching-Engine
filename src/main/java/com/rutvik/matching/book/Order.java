package com.rutvik.matching.book;

/**
 * Mutable on purpose - this only lives inside the engine thread. Anything that
 * leaves the engine is an immutable ExecutionReport or Trade.
 */
public final class Order {

    private final long id;
    private final String clientId;
    private final String clientOrderId;
    private final Side side;
    private final OrderType type;
    private final TimeInForce timeInForce;

    long price;
    long quantity;
    long filled;
    long priority;

    Order prev;
    Order next;
    PriceLevel level;

    public Order(long id, String clientId, String clientOrderId, Side side, OrderType type,
                 TimeInForce timeInForce, long price, long quantity) {
        this.id = id;
        this.clientId = clientId;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.type = type;
        this.timeInForce = timeInForce;
        this.price = price;
        this.quantity = quantity;
    }

    public static Order limit(long id, String clientId, Side side, long price, long quantity) {
        return new Order(id, clientId, "c" + id, side, OrderType.LIMIT, TimeInForce.GTC, price, quantity);
    }

    public static Order market(long id, String clientId, Side side, long quantity) {
        return new Order(id, clientId, "c" + id, side, OrderType.MARKET, TimeInForce.IOC, 0, quantity);
    }

    public long id() { return id; }
    public String clientId() { return clientId; }
    public String clientOrderId() { return clientOrderId; }
    public Side side() { return side; }
    public OrderType type() { return type; }
    public TimeInForce timeInForce() { return timeInForce; }
    public long price() { return price; }
    public long quantity() { return quantity; }
    public long filled() { return filled; }
    public long priority() { return priority; }

    public long leaves() {
        return quantity - filled;
    }

    boolean isMarket() {
        return type == OrderType.MARKET;
    }

    @Override
    public String toString() {
        return side + " " + leaves() + "/" + quantity + " @ " + price + " #" + id;
    }
}
