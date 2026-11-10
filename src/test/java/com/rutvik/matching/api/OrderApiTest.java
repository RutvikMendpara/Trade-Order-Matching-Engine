package com.rutvik.matching.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "engine.journal-dir=")
@AutoConfigureMockMvc
@DirtiesContext
class OrderApiTest {

    @Autowired
    MockMvc mvc;

    private ResultActions order(String json) throws Exception {
        return mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String limit(String client, String clOrdId, String side, String price, int qty) {
        return """
                {"clientId":"%s","clientOrderId":"%s","symbol":"TCS","side":"%s","price":%s,"quantity":%d}
                """.formatted(client, clOrdId, side, price, qty);
    }

    @Test
    void orderLifecycle() throws Exception {
        order(limit("alice", "a1", "SELL", "3400.00", 100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        order(limit("bob", "b1", "BUY", "3400.50", 60))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.reports[1].lastPrice").value(3400.00));

        mvc.perform(get("/book/TCS"))
                .andExpect(jsonPath("$.asks", hasSize(1)))
                .andExpect(jsonPath("$.asks[0].quantity").value(40))
                .andExpect(jsonPath("$.lastPrice").value(3400.00));

        String body = order(limit("carol", "c1", "BUY", "3399.00", 10)).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"orderId\":(\\d+).*", "$1"));
        mvc.perform(delete("/orders/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(delete("/orders/" + id)).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void riskChecks() throws Exception {
        order(limit("x", "r1", "BUY", "3400.03", 10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("price not on tick size 0.05"));
        order(limit("x", "r2", "BUY", "5000.00", 10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.startsWith("price outside 10% band")));
        order(limit("x", "r3", "BUY", "3400.00", 1_000_000))
                .andExpect(status().isUnprocessableEntity());
        order("""
                {"clientId":"x","clientOrderId":"r4","symbol":"NOPE","side":"BUY","price":10,"quantity":1}
                """).andExpect(status().isUnprocessableEntity());
        order("""
                {"clientId":"x,y","clientOrderId":"r5","symbol":"TCS","side":"BUY","price":3400,"quantity":1}
                """).andExpect(status().isBadRequest());
    }

    @Test
    void resendingSameClientOrderIdIsNotProcessedTwice() throws Exception {
        order(limit("dave", "same", "BUY", "3300.00", 5)).andExpect(status().isOk());
        order(limit("dave", "same", "BUY", "3300.00", 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("duplicate client order id"));
        mvc.perform(get("/book/TCS?depth=50"))
                .andExpect(jsonPath("$.bids[?(@.price == 3300.0)].quantity").value(5));
    }

    @Test
    void unknownBook() throws Exception {
        mvc.perform(get("/book/NOPE")).andExpect(status().isNotFound());
    }
}
