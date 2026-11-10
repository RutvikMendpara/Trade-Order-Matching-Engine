package com.rutvik.matching.book;

public record Trade(
        long tradeId,
        String symbol,
        long price,
        long quantity,
        long buyOrderId,
        long sellOrderId,
        String buyClientId,
        String sellClientId,
        Side aggressor) {
}
