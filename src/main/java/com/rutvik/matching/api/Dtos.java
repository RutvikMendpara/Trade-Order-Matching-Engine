package com.rutvik.matching.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rutvik.matching.book.BookSnapshot;
import com.rutvik.matching.book.ExecutionReport;
import com.rutvik.matching.book.OrderStatus;
import com.rutvik.matching.book.OrderType;
import com.rutvik.matching.book.Side;
import com.rutvik.matching.book.TimeInForce;
import com.rutvik.matching.book.Trade;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

final class Dtos {

    private Dtos() {
    }

    private static final String ID = "[A-Za-z0-9_-]{1,32}";

    record OrderRequest(
            @NotBlank @Pattern(regexp = ID) String clientId,
            @NotBlank @Pattern(regexp = ID) String clientOrderId,
            @NotBlank String symbol,
            @NotNull Side side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            @Positive long quantity) {

        OrderType typeOrDefault() {
            return type == null ? OrderType.LIMIT : type;
        }

        TimeInForce timeInForceOrDefault() {
            if (timeInForce != null) {
                return timeInForce;
            }
            return typeOrDefault() == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC;
        }
    }

    record AmendRequest(@Positive long quantity, @NotNull @Positive BigDecimal price) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderResponse(Long orderId, OrderStatus status, String reason, List<Report> reports) {

        static OrderResponse of(long orderId, List<ExecutionReport> reports) {
            ExecutionReport last = reports.isEmpty() ? null : reports.get(reports.size() - 1);
            for (int i = reports.size() - 1; i >= 0; i--) {
                if (reports.get(i).orderId() == orderId) {
                    last = reports.get(i);
                    break;
                }
            }
            return new OrderResponse(orderId, last == null ? null : last.status(),
                    last == null ? null : last.reason(), reports.stream().map(Report::of).toList());
        }

        static OrderResponse rejected(String reason) {
            return new OrderResponse(null, OrderStatus.REJECTED, reason, List.of());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(long orderId, String clientId, String clientOrderId, String symbol, Side side, OrderType type,
                  OrderStatus status, BigDecimal price, long quantity, long filled, long leaves,
                  BigDecimal lastPrice, Long lastQuantity, String reason) {

        static Report of(ExecutionReport r) {
            boolean fill = r.lastQuantity() > 0;
            return new Report(r.orderId(), r.clientId(), r.clientOrderId(), r.symbol(), r.side(), r.type(),
                    r.status(), r.price() > 0 ? Prices.toRupees(r.price()) : null, r.quantity(),
                    r.filledQuantity(), r.leavesQuantity(), fill ? Prices.toRupees(r.lastPrice()) : null,
                    fill ? r.lastQuantity() : null, r.reason());
        }
    }

    record Level(BigDecimal price, long quantity, int orders) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Book(String symbol, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal spread, BigDecimal lastPrice,
                List<Level> bids, List<Level> asks) {

        static Book of(BookSnapshot s) {
            BigDecimal bid = s.bestBid() > 0 ? Prices.toRupees(s.bestBid()) : null;
            BigDecimal ask = s.bestAsk() > 0 ? Prices.toRupees(s.bestAsk()) : null;
            return new Book(s.symbol(), bid, ask, bid != null && ask != null ? ask.subtract(bid) : null,
                    s.lastTradePrice() > 0 ? Prices.toRupees(s.lastTradePrice()) : null,
                    levels(s.bids()), levels(s.asks()));
        }

        private static List<Level> levels(List<BookSnapshot.Level> levels) {
            return levels.stream().map(l -> new Level(Prices.toRupees(l.price()), l.quantity(), l.orders())).toList();
        }
    }

    record TradeView(long tradeId, String symbol, BigDecimal price, long quantity, long buyOrderId, long sellOrderId,
                     Side aggressor) {

        static TradeView of(Trade t) {
            return new TradeView(t.tradeId(), t.symbol(), Prices.toRupees(t.price()), t.quantity(), t.buyOrderId(),
                    t.sellOrderId(), t.aggressor());
        }
    }
}
