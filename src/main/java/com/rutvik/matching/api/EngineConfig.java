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
        return new MatchingEngine(symbols, props.shards(), props.journalDir(), props.fsync());
    }
}
