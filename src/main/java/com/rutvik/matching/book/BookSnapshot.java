package com.rutvik.matching.book;

import java.util.List;

public record BookSnapshot(String symbol, List<Level> bids, List<Level> asks, long lastTradePrice) {

    public record Level(long price, long quantity, int orders) {
    }

    public long bestBid() {
        return bids.isEmpty() ? 0 : bids.get(0).price();
    }

    public long bestAsk() {
        return asks.isEmpty() ? 0 : asks.get(0).price();
    }
}
