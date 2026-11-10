package com.rutvik.matching.api;

import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Random order flow so the UI has something to show. Turn on with --engine.demo=true */
@Component
@ConditionalOnProperty(name = "engine.demo", havingValue = "true")
class DemoTrader {

    private final OrderService orders;
    private final AtomicLong counter = new AtomicLong();

    DemoTrader(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 40)
    void trade() {
        var random = ThreadLocalRandom.current();
        String symbol = orders.symbols().get(random.nextInt(orders.symbols().size()));
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long tick = orders.tick(symbol);
        long mid = orders.lastPrice(symbol);

        // mostly passive orders a few ticks off the last price, sometimes an aggressive one
        long offset = random.nextInt(1, 15) * tick;
        boolean aggressive = random.nextInt(10) == 0;
        long price = (side == Side.BUY) == aggressive ? mid + offset : mid - offset;
        long qty = random.nextInt(1, 40) * 5L;
        boolean market = random.nextInt(25) == 0;

        orders.submit(new Dtos.OrderRequest("bot" + random.nextInt(8), "d" + counter.incrementAndGet(), symbol, side,
                market ? OrderType.MARKET : OrderType.LIMIT, market ? TimeInForce.IOC : TimeInForce.GTC,
                market ? null : BigDecimal.valueOf(price, 2), qty));
    }
}
