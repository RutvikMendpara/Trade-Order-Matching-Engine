package com.rutvik.matching.engine;

public class EngineBusyException extends RuntimeException {

    public EngineBusyException(String symbol) {
        super("engine queue full for " + symbol + ", retry later");
    }
}
