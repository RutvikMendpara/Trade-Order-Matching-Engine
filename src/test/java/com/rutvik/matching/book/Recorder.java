package com.rutvik.matching.book;

import java.util.ArrayList;
import java.util.List;

class Recorder implements BookListener {

    final List<ExecutionReport> reports = new ArrayList<>();
    final List<Trade> trades = new ArrayList<>();

    @Override
    public void onReport(ExecutionReport report) {
        reports.add(report);
    }

    @Override
    public void onTrade(Trade trade) {
        trades.add(trade);
    }

    ExecutionReport last(long orderId) {
        for (int i = reports.size() - 1; i >= 0; i--) {
            if (reports.get(i).orderId() == orderId) {
                return reports.get(i);
            }
        }
        throw new AssertionError("no report for order " + orderId);
    }

    void clear() {
        reports.clear();
        trades.clear();
    }
}
