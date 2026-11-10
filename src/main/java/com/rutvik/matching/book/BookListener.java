package com.rutvik.matching.book;

public interface BookListener {

    void onReport(ExecutionReport report);

    void onTrade(Trade trade);

    BookListener NONE = new BookListener() {
        @Override
        public void onReport(ExecutionReport report) {
        }

        @Override
        public void onTrade(Trade trade) {
        }
    };
}
