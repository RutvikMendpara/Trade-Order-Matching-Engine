package com.rutvik.matching.engine;

import com.rutvik.matching.book.Order;
import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;

/**
 * Everything that can change a book. These are what gets journaled, so replaying
 * the same commands in the same order has to give the same trades.
 */
public sealed interface Command {

    String symbol();

    record NewOrder(long orderId, String clientId, String clientOrderId, String symbol, Side side,
                    OrderType type, TimeInForce timeInForce, long price, long quantity) implements Command {

        Order toOrder() {
            return new Order(orderId, clientId, clientOrderId, side, type, timeInForce, price, quantity);
        }
    }

    record CancelOrder(long orderId, String symbol) implements Command {
    }

    record AmendOrder(long orderId, String symbol, long quantity, long price) implements Command {
    }

    record EndOfDay(String symbol) implements Command {
    }
}
