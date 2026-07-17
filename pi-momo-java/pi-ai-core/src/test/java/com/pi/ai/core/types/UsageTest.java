package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Usage}, {@link Usage.Cost}, and {@link MutableUsage}.
 */
class UsageTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // --- Usage record ---

    @Test
    void usage_recordFieldsAccessible() {
        var cost = new Usage.Cost(0.01, 0.02, 0.005, 0.003, 0.038);
        var usage = new Usage(100, 200, 50, 30, 380, cost);

        assertThat(usage.input()).isEqualTo(100);
        assertThat(usage.output()).isEqualTo(200);
        assertThat(usage.cacheRead()).isEqualTo(50);
        assertThat(usage.cacheWrite()).isEqualTo(30);
        assertThat(usage.totalTokens()).isEqualTo(380);
        assertThat(usage.cost()).isEqualTo(cost);
    }

    @Test
    void usage_jacksonRoundTrip() throws JsonProcessingException {
        var cost = new Usage.Cost(0.01, 0.02, 0.005, 0.003, 0.038);
        var usage = new Usage(100, 200, 50, 30, 380, cost);

        String json = MAPPER.writeValueAsString(usage);
        Usage deserialized = MAPPER.readValue(json, Usage.class);

        assertThat(deserialized).isEqualTo(usage);
    }

    @Test
    void usage_deserializesFromJson() throws JsonProcessingException {
        String json = """
                {
                  "input": 10,
                  "output": 20,
                  "cacheRead": 5,
                  "cacheWrite": 3,
                  "totalTokens": 38,
                  "cost": {
                    "input": 0.001,
                    "output": 0.002,
                    "cacheRead": 0.0005,
                    "cacheWrite": 0.0003,
                    "total": 0.0038
                  }
                }
                """;
        Usage usage = MAPPER.readValue(json, Usage.class);

        assertThat(usage.input()).isEqualTo(10);
        assertThat(usage.output()).isEqualTo(20);
        assertThat(usage.cost().total()).isEqualTo(0.0038);
    }

    @Test
    void usage_deserializesWithNullCost() throws JsonProcessingException {
        String json = """
                {
                  "input": 10,
                  "output": 20,
                  "cacheRead": 0,
                  "cacheWrite": 0,
                  "totalTokens": 30,
                  "cost": null
                }
                """;
        Usage usage = MAPPER.readValue(json, Usage.class);

        assertThat(usage.cost()).isNull();
    }

    @Test
    void usage_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {
                  "input": 10,
                  "output": 20,
                  "cacheRead": 0,
                  "cacheWrite": 0,
                  "totalTokens": 30,
                  "cost": null,
                  "unknownField": "should be ignored"
                }
                """;
        Usage usage = MAPPER.readValue(json, Usage.class);

        assertThat(usage.input()).isEqualTo(10);
    }

    // --- Usage.Cost record ---

    @Test
    void cost_jacksonRoundTrip() throws JsonProcessingException {
        var cost = new Usage.Cost(1.5, 2.5, 0.75, 0.25, 5.0);

        String json = MAPPER.writeValueAsString(cost);
        Usage.Cost deserialized = MAPPER.readValue(json, Usage.Cost.class);

        assertThat(deserialized).isEqualTo(cost);
    }

    // --- MutableUsage ---

    @Test
    void mutableUsage_startsAtZero() {
        var mu = new MutableUsage();

        assertThat(mu.getInput()).isZero();
        assertThat(mu.getOutput()).isZero();
        assertThat(mu.getCacheRead()).isZero();
        assertThat(mu.getCacheWrite()).isZero();
        assertThat(mu.getCost()).isNull();
        assertThat(mu.computeTotalTokens()).isZero();
    }

    @Test
    void mutableUsage_accumulatesTokens() {
        var mu = new MutableUsage();

        mu.addInput(10);
        mu.addInput(5);
        mu.addOutput(20);
        mu.addCacheRead(3);
        mu.addCacheWrite(2);

        assertThat(mu.getInput()).isEqualTo(15);
        assertThat(mu.getOutput()).isEqualTo(20);
        assertThat(mu.getCacheRead()).isEqualTo(3);
        assertThat(mu.getCacheWrite()).isEqualTo(2);
        assertThat(mu.computeTotalTokens()).isEqualTo(40);
    }

    @Test
    void mutableUsage_setCostStoresCost() {
        var mu = new MutableUsage();
        var cost = new Usage.Cost(0.01, 0.02, 0.005, 0.003, 0.038);

        mu.setCost(cost);

        assertThat(mu.getCost()).isEqualTo(cost);
    }

    @Test
    void mutableUsage_toUsageProducesCorrectRecord() {
        var mu = new MutableUsage();
        mu.addInput(100);
        mu.addOutput(200);
        mu.addCacheRead(50);
        mu.addCacheWrite(30);
        var cost = new Usage.Cost(0.01, 0.02, 0.005, 0.003, 0.038);
        mu.setCost(cost);

        Usage usage = mu.toUsage();

        assertThat(usage.input()).isEqualTo(100);
        assertThat(usage.output()).isEqualTo(200);
        assertThat(usage.cacheRead()).isEqualTo(50);
        assertThat(usage.cacheWrite()).isEqualTo(30);
        assertThat(usage.totalTokens()).isEqualTo(380);
        assertThat(usage.cost()).isEqualTo(cost);
    }

    @Test
    void mutableUsage_toUsageWithNullCost() {
        var mu = new MutableUsage();
        mu.addInput(10);
        mu.addOutput(20);

        Usage usage = mu.toUsage();

        assertThat(usage.cost()).isNull();
        assertThat(usage.totalTokens()).isEqualTo(30);
    }

    @Test
    void mutableUsage_computeTotalTokensSumsAllFields() {
        var mu = new MutableUsage();
        mu.addInput(1);
        mu.addOutput(2);
        mu.addCacheRead(3);
        mu.addCacheWrite(4);

        assertThat(mu.computeTotalTokens()).isEqualTo(10);
    }
}
