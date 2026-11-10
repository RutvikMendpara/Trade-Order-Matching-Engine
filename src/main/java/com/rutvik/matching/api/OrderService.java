package com.rutvik.matching.api;

import com.rutvik.matching.book.BookListener;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Trade;
import com.rutvik.matching.engine.Command;
import com.rutvik.matching.engine.EngineBusyException;
import com.rutvik.matching.engine.MatchingEngine;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Gateway side: validation, pre-trade risk, order id assignment, and a read model
 * built from the engine's output so the API can answer "what happened to my order".
 */
@Service
public class OrderService implements BookListener {

    private static final int RECENT_TRADES = 50;

    record Rules(long tick, long referencePrice, long maxQuantity) {
    }

    sealed interface Result {
        record Accepted(long orderId, List<ExecutionReport> reports) implements Result {
        }

        record Rejected(String reason) implements Result {
        }

        record Duplicate(long orderId) implements Result {
        }
    }

    private final MatchingEngine engine;
    private final int priceBandPercent;
    private final Map<String, Rules> rules = new LinkedHashMap<>();

    private final AtomicLong nextOrderId;
    private final Map<String, Long> clientOrderIds = new ConcurrentHashMap<>();
    private final Map<Long, String> orderSymbols = new ConcurrentHashMap<>();
    private final Map<Long, ExecutionReport> latestReports = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPrices = new ConcurrentHashMap<>();
    private final Map<String, Deque<Trade>> recentTrades = new ConcurrentHashMap<>();

    public OrderService(MatchingEngine engine, EngineProperties props) {
        this.engine = engine;
        this.priceBandPercent = props.priceBandPercent();
        for (var s : props.symbols()) {
            rules.put(s.name(), new Rules(Prices.toPaise(s.tickSize()), Prices.toPaise(s.referencePrice()), s.maxQuantity()));
            recentTrades.put(s.name(), new ConcurrentLinkedDeque<>());
        }
        engine.addListener(this);
        engine.start(); // recovery goes through onReport/onTrade, rebuilding everything below
        this.nextOrderId = new AtomicLong(engine.highestOrderId() + 1);
        for (String symbol : rules.keySet()) {
            long last = engine.depth(symbol, 0).join().lastTradePrice();
            if (last > 0) {
                lastPrices.putIfAbsent(symbol, last);
            }
        }
    }

    public Result submit(Dtos.OrderRequest request) {
        Rules r = rules.get(request.symbol());
        if (r == null) {
            return new Result.Rejected("unknown symbol " + request.symbol());
        }
        OrderType type = request.typeOrDefault();
        long price = 0;
        if (type == OrderType.LIMIT) {
            if (request.price() == null || request.price().signum() <= 0) {
                return new Result.Rejected("limit order needs a positive price");
            }
            try {
                price = Prices.toPaise(request.price());
            } catch (ArithmeticException e) {
                return new Result.Rejected("price has too many decimals");
            }
        } else if (request.price() != null) {
            return new Result.Rejected("market orders don't take a price");
        }

        String problem = riskCheck(request.symbol(), r, type, price, request.quantity());
        if (problem != null) {
            return new Result.Rejected(problem);
        }

        long orderId = nextOrderId.getAndIncrement();
        Long existing = clientOrderIds.putIfAbsent(request.clientId() + "|" + request.clientOrderId(), orderId);
        if (existing != null) {
            return new Result.Duplicate(existing);
        }
        orderSymbols.put(orderId, request.symbol());

        var command = new Command.NewOrder(orderId, request.clientId(), request.clientOrderId(), request.symbol(),
                request.side(), type, request.timeInForceOrDefault(), price, request.quantity());
        return new Result.Accepted(orderId, await(engine.submit(command)));
    }

    public Result cancel(long orderId) {
        String symbol = orderSymbols.get(orderId);
        if (symbol == null) {
            return new Result.Rejected("unknown order " + orderId);
        }
        return new Result.Accepted(orderId, await(engine.submit(new Command.CancelOrder(orderId, symbol))));
    }

    public Result amend(long orderId, Dtos.AmendRequest request) {
        String symbol = orderSymbols.get(orderId);
        if (symbol == null) {
            return new Result.Rejected("unknown order " + orderId);
        }
        Rules r = rules.get(symbol);
        long price;
        try {
            price = Prices.toPaise(request.price());
        } catch (ArithmeticException e) {
            return new Result.Rejected("price has too many decimals");
        }
        String problem = riskCheck(symbol, r, OrderType.LIMIT, price, request.quantity());
        if (problem != null) {
            return new Result.Rejected(problem);
        }
        return new Result.Accepted(orderId,
                await(engine.submit(new Command.AmendOrder(orderId, symbol, request.quantity(), price))));
    }

    public void endOfDay() {
        List<CompletableFuture<List<ExecutionReport>>> pending = new ArrayList<>();
        for (String symbol : rules.keySet()) {
            pending.add(engine.submit(new Command.EndOfDay(symbol)));
        }
        pending.forEach(this::await);
    }

    private String riskCheck(String symbol, Rules r, OrderType type, long price, long quantity) {
        if (quantity <= 0) {
            return "quantity must be positive";
        }
        if (quantity > r.maxQuantity()) {
            return "quantity above max order size " + r.maxQuantity();
        }
        if (type == OrderType.MARKET) {
            return null;
        }
        if (price % r.tick() != 0) {
            return "price not on tick size " + Prices.toRupees(r.tick());
        }
        // fat finger check against last trade, or the configured reference before the first trade
        long reference = lastPrices.getOrDefault(symbol, r.referencePrice());
        if (Math.abs(price - reference) * 100 > reference * priceBandPercent) {
            return "price outside " + priceBandPercent + "% band around " + Prices.toRupees(reference);
        }
        return null;
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof EngineBusyException busy) {
                throw busy;
            }
            throw new IllegalStateException("engine failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("engine did not respond", e);
        }
    }

    public BookSnapshot book(String symbol, int depth) {
        return await(engine.depth(symbol, depth));
    }

    public boolean hasSymbol(String symbol) {
        return rules.containsKey(symbol);
    }

    public List<String> symbols() {
        return List.copyOf(rules.keySet());
    }

    public ExecutionReport latest(long orderId) {
        return latestReports.get(orderId);
    }

    public List<Trade> recentTrades(String symbol) {
        Deque<Trade> trades = recentTrades.get(symbol);
        return trades == null ? List.of() : List.copyOf(trades);
    }

    public long lastPrice(String symbol) {
        return lastPrices.getOrDefault(symbol, rules.get(symbol).referencePrice());
    }

    public long tick(String symbol) {
        return rules.get(symbol).tick();
    }

    // called on engine threads

    @Override
    public void onReport(ExecutionReport report) {
        if (report.clientId() == null) {
            return; // cancel/amend reject, nothing to store against the order
        }
        latestReports.put(report.orderId(), report);
        clientOrderIds.putIfAbsent(report.clientId() + "|" + report.clientOrderId(), report.orderId());
        orderSymbols.putIfAbsent(report.orderId(), report.symbol());
    }

    @Override
    public void onTrade(Trade trade) {
        lastPrices.put(trade.symbol(), trade.price());
        Deque<Trade> trades = recentTrades.get(trade.symbol());
        trades.addFirst(trade);
        while (trades.size() > RECENT_TRADES) {
            trades.pollLast();
        }
    }
}
