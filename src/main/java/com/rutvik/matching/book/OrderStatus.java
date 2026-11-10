package com.rutvik.matching.book;

public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    REPLACED,
    CANCELLED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
