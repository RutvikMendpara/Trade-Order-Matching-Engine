package com.rutvik.matching.book;

/**
 * All resting orders at one price, oldest first. Intrusive doubly linked list so
 * we can unlink any order in O(1) when it gets cancelled.
 */
final class PriceLevel {

    final long price;
    final Long key; // boxed once, reused for map removes
    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    PriceLevel(long price) {
        this.price = price;
        this.key = price;
    }

    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.leaves();
        orderCount++;
    }

    void remove(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalQuantity -= order.leaves();
        orderCount--;
        order.prev = null;
        order.next = null;
        order.level = null;
    }

    boolean isEmpty() {
        return head == null;
    }
}
