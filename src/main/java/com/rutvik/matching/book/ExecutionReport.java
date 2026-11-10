package com.rutvik.matching.book;

/**
 * What goes back to the client. Prices are in paise, same as everywhere else in the engine.
 * lastPrice/lastQuantity are only set on fills.
 */
public record ExecutionReport(
        long orderId,
        String clientId,
        String clientOrderId,
        String symbol,
        Side side,
        OrderType type,
        OrderStatus status,
        long price,
        long quantity,
        long filledQuantity,
        long leavesQuantity,
        long lastPrice,
        long lastQuantity,
        String reason) {

    public static ExecutionReport cancelReject(long orderId, String symbol, String reason) {
        return new ExecutionReport(orderId, null, null, symbol, null, null, OrderStatus.REJECTED,
                0, 0, 0, 0, 0, 0, reason);
    }
}
