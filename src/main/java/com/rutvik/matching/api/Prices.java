package com.rutvik.matching.api;

import java.math.BigDecimal;

/** Rupees on the wire, paise inside the engine. Never a double. */
final class Prices {

    private Prices() {
    }

    static long toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).longValueExact();
    }

    static BigDecimal toRupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }
}
