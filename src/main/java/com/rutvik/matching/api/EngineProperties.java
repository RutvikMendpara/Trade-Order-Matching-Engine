package com.rutvik.matching.api;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("engine")
public record EngineProperties(
        int shards,
        Path journalDir,
        boolean fsync,
        long snapshotEvery,
        int queueSize,
        int priceBandPercent,
        boolean demo,
        List<SymbolProperties> symbols) {

    public record SymbolProperties(String name, BigDecimal tickSize, BigDecimal referencePrice, long maxQuantity) {
    }
}
