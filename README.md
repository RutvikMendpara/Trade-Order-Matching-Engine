# Trade Order Matching Engine

A small stock exchange matching engine in Java 21. It takes buy and sell orders, keeps an order book per symbol and matches them using price-time priority.

![screenshot](screenshot.png)

## What it does

- Limit and market orders, partial fills
- Cancel and amend (reducing qty keeps your place in the queue, anything else loses it)
- Time in force: GTC, DAY, IOC, FOK
- Self-trade prevention
- Pre-trade risk checks: tick size, max order size, 10% price band, duplicate client order id
- Multiple symbols, each book owned by a single thread
- Journal of every command, books are rebuilt by replaying it on restart
- REST API and a small web page showing the depth and trades

## Run it

```
./mvnw spring-boot:run
```

Open http://localhost:8080. To get random orders flowing so the page isn't empty:

```
./mvnw spring-boot:run -Dspring-boot.run.arguments=--engine.demo=true
```

## API

```
POST   /orders              new order
PATCH  /orders/{id}         amend   {"quantity": 50, "price": 1500.10}
DELETE /orders/{id}         cancel
GET    /orders/{id}         latest execution report
GET    /book/{symbol}       top 5 levels (?depth=n for more)
GET    /trades/{symbol}     recent trades
```

```
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{
  "clientId": "alice", "clientOrderId": "a1", "symbol": "INFY",
  "side": "BUY", "type": "LIMIT", "price": 1500.05, "quantity": 100
}'
```

## How it works

```
REST -> risk checks -> per-shard queue -> journal -> order book -> execution reports / trades
```

- Each symbol belongs to one engine thread. Only that thread touches the book, so there are no locks in the matching code and the same input always gives the same trades.
- More symbols means more shards (threads), not locks.
- Prices are stored as `long` paise, never `double`.
- Bids and asks are `TreeMap<Long, PriceLevel>`. Each level is a doubly linked list of orders (FIFO), and a `HashMap` of order id to order gives O(1) cancel.
- Commands are written to the journal before they're applied, and the client gets its response only after the journal is flushed. On startup the journal is replayed to rebuild the books. A half-written last line from a crash is dropped.

Matching rules:

1. A buy at P matches asks at P or lower, lowest first. A sell matches bids at P or higher, highest first.
2. Trades happen at the resting order's price.
3. Same price, oldest order fills first.
4. Leftover limit qty rests in the book, leftover market qty is cancelled.

## Tests

```
./mvnw test
```

- One test per matching rule, plus edge cases (empty book, exact fill, cancel after fill, IOC/FOK, self-trade...)
- Property tests with jqwik: 1000 random sequences of new/cancel/amend, and after every step it checks the book is never crossed, quantity is conserved (submitted = filled + resting + cancelled) and FIFO holds inside each level
- Replay tests: restart gives identical books, replaying the same journal gives identical trades
- 8 threads submitting 40k orders at once, nothing lost or duplicated
- API tests with MockMvc

## Benchmarks

Apple M4 (10 cores), 24 GB, JDK 21.0.10.

JMH (`./mvnw test-compile exec:exec@jmh`), book holding 10k orders per side:

| Operation | Time |
|---|---|
| add + cancel | 36 ns |
| match one order + refill | 52 ns |
| sweep 5 levels (500 fills) + refill 500 | 19.8 us |

Load test (`./mvnw test-compile exec:exec@load`), random flow of limits, markets, cancels and amends:

| Run | Throughput | p50 | p99 | p99.9 |
|---|---|---|---|---|
| Order book only, 5M commands, 1 thread | 6.9 - 9.3M/sec | 0.08 us | 0.3 - 0.4 us | 0.6 - 1.0 us |
| Full engine with journal, 2M commands, 256 in flight per producer | 3.7M/sec | 190 us | 610 us | 18 ms |
| Full engine with journal, 1 in flight per producer (round trip) | 280k/sec | 8.6 us | 34 us | 64 us |

The full engine numbers include the hop between threads and the journal flush. With 256 orders in flight per producer, most of the latency is just time spent waiting in the queue. The random flow doesn't cancel much, so the book ends up with about 1M resting orders, which is a decent stress case.
