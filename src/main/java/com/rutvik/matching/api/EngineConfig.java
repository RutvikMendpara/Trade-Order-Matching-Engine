package com.rutvik.matching.api;

import com.rutvik.matching.engine.MatchingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EngineConfig {

    // started by OrderService once it has registered itself as a listener
    @Bean(destroyMethod = "close")
    MatchingEngine matchingEngine(EngineProperties props) {
        var symbols = props.symbols().stream().map(EngineProperties.SymbolProperties::name).toList();
        // the gateway rejects with 503 when a shard queue is full instead of piling up latency
        var options = new MatchingEngine.Options(props.journalDir(), props.fsync(), props.snapshotEvery(),
                props.queueSize(), true);
        return new MatchingEngine(symbols, props.shards(), options);
    }
}
