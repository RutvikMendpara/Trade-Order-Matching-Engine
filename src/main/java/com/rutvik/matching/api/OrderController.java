package com.rutvik.matching.api;

import com.rutvik.matching.book.ExecutionReport;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    ResponseEntity<Dtos.OrderResponse> submit(@Valid @RequestBody Dtos.OrderRequest request) {
        return respond(orders.submit(request));
    }

    @DeleteMapping("/orders/{id}")
    ResponseEntity<Dtos.OrderResponse> cancel(@PathVariable long id) {
        return respond(orders.cancel(id));
    }

    @PatchMapping("/orders/{id}")
    ResponseEntity<Dtos.OrderResponse> amend(@PathVariable long id, @Valid @RequestBody Dtos.AmendRequest request) {
        return respond(orders.amend(id, request));
    }

    @GetMapping("/orders/{id}")
    ResponseEntity<Dtos.Report> status(@PathVariable long id) {
        ExecutionReport report = orders.latest(id);
        return report == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(Dtos.Report.of(report));
    }

    @GetMapping("/book/{symbol}")
    ResponseEntity<Dtos.Book> book(@PathVariable String symbol, @RequestParam(defaultValue = "5") int depth) {
        if (!orders.hasSymbol(symbol)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Dtos.Book.of(orders.book(symbol, Math.clamp(depth, 1, 50))));
    }

    @GetMapping("/trades/{symbol}")
    ResponseEntity<List<Dtos.TradeView>> trades(@PathVariable String symbol) {
        if (!orders.hasSymbol(symbol)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(orders.recentTrades(symbol).stream().map(Dtos.TradeView::of).toList());
    }

    @GetMapping("/symbols")
    List<String> symbols() {
        return orders.symbols();
    }

    @PostMapping("/admin/end-of-day")
    Map<String, String> endOfDay() {
        orders.endOfDay();
        return Map.of("status", "DAY orders expired");
    }

    private ResponseEntity<Dtos.OrderResponse> respond(OrderService.Result result) {
        return switch (result) {
            case OrderService.Result.Accepted a -> ResponseEntity.ok(Dtos.OrderResponse.of(a.orderId(), a.reports()));
            case OrderService.Result.Rejected r -> ResponseEntity.unprocessableEntity()
                    .body(Dtos.OrderResponse.rejected(r.reason()));
            case OrderService.Result.Duplicate d -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Dtos.OrderResponse(d.orderId(), null, "duplicate client order id", List.of()));
        };
    }
}
