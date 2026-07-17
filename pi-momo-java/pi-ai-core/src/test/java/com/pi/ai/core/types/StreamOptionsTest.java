package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：ThinkingBudgets、StreamOptions、SimpleStreamOptions 的
 * Builder 模式、Jackson 序列化/反序列化和边界情况。
 */
class StreamOptionsTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== ThinkingBudgets ====================

    @Test
    void thinkingBudgets_allFields_roundTrip() throws JsonProcessingException {
        var budgets = new ThinkingBudgets(1024, 2048, 8192, 16384);
        String json = MAPPER.writeValueAsString(budgets);
        var deserialized = MAPPER.readValue(json, ThinkingBudgets.class);
        assertThat(deserialized).isEqualTo(budgets);
    }

    @Test
    void thinkingBudgets_allNull_emptyJson() throws JsonProcessingException {
        var budgets = new ThinkingBudgets(null, null, null, null);
        String json = MAPPER.writeValueAsString(budgets);
        assertThat(json).isEqualTo("{}");
        var deserialized = MAPPER.readValue(json, ThinkingBudgets.class);
        assertThat(deserialized).isEqualTo(budgets);
    }

    @Test
    void thinkingBudgets_partialFields() throws JsonProcessingException {
        var budgets = new ThinkingBudgets(null, 2048, null, 16384);
        String json = MAPPER.writeValueAsString(budgets);
        assertThat(json).doesNotContain("\"minimal\"");
        assertThat(json).contains("\"low\":2048");
        assertThat(json).doesNotContain("\"medium\"");
        assertThat(json).contains("\"high\":16384");
        var deserialized = MAPPER.readValue(json, ThinkingBudgets.class);
        assertThat(deserialized).isEqualTo(budgets);
    }

    @Test
    void thinkingBudgets_jsonFieldNames() throws JsonProcessingException {
        var budgets = new ThinkingBudgets(100, 200, 300, 400);
        String json = MAPPER.writeValueAsString(budgets);
        assertThat(json).contains("\"minimal\"");
        assertThat(json).contains("\"low\"");
        assertThat(json).contains("\"medium\"");
        assertThat(json).contains("\"high\"");
    }

    @Test
    void thinkingBudgets_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {"minimal":1024,"low":2048,"unknownField":"value"}
                """;
        var budgets = MAPPER.readValue(json, ThinkingBudgets.class);
        assertThat(budgets.minimal()).isEqualTo(1024);
        assertThat(budgets.low()).isEqualTo(2048);
        assertThat(budgets.medium()).isNull();
        assertThat(budgets.high()).isNull();
    }

    // ==================== StreamOptions Builder ====================

    @Test
    void streamOptions_builder_allFields() {
        var opts = StreamOptions.builder()
                .temperature(0.7)
                .maxTokens(4096)
                .apiKey("sk-test")
                .cacheRetention(CacheRetention.SHORT)
                .sessionId("session-123")
                .headers(Map.of("X-Custom", "value"))
                .transport(Transport.SSE)
                .maxRetryDelayMs(30000)
                .metadata(Map.of("user_id", "u123"))
                .onPayload((payload, model) -> payload)
                .build();

        assertThat(opts.getTemperature()).isEqualTo(0.7);
        assertThat(opts.getMaxTokens()).isEqualTo(4096);
        assertThat(opts.getApiKey()).isEqualTo("sk-test");
        assertThat(opts.getCacheRetention()).isEqualTo(CacheRetention.SHORT);
        assertThat(opts.getSessionId()).isEqualTo("session-123");
        assertThat(opts.getHeaders()).containsEntry("X-Custom", "value");
        assertThat(opts.getTransport()).isEqualTo(Transport.SSE);
        assertThat(opts.getMaxRetryDelayMs()).isEqualTo(30000);
        assertThat(opts.getMetadata()).containsEntry("user_id", "u123");
        assertThat(opts.getOnPayload()).isNotNull();
    }

    @Test
    void streamOptions_builder_defaults() {
        var opts = StreamOptions.builder().build();

        assertThat(opts.getTemperature()).isNull();
        assertThat(opts.getMaxTokens()).isNull();
        assertThat(opts.getApiKey()).isNull();
        assertThat(opts.getCacheRetention()).isNull();
        assertThat(opts.getSessionId()).isNull();
        assertThat(opts.getHeaders()).isNull();
        assertThat(opts.getTransport()).isNull();
        assertThat(opts.getMaxRetryDelayMs()).isNull();
        assertThat(opts.getMetadata()).isNull();
        assertThat(opts.getOnPayload()).isNull();
    }

    @Test
    void streamOptions_jackson_roundTrip() throws JsonProcessingException {
        var opts = StreamOptions.builder()
                .temperature(0.5)
                .maxTokens(2048)
                .apiKey("sk-key")
                .cacheRetention(CacheRetention.LONG)
                .sessionId("s1")
                .headers(Map.of("Auth", "Bearer x"))
                .transport(Transport.WEBSOCKET)
                .maxRetryDelayMs(60000)
                .metadata(Map.of("key", "val"))
                .build();

        String json = MAPPER.writeValueAsString(opts);
        var deserialized = MAPPER.readValue(json, StreamOptions.class);

        assertThat(deserialized).isEqualTo(opts);
    }

    @Test
    void streamOptions_nullFields_omittedInJson() throws JsonProcessingException {
        var opts = StreamOptions.builder().temperature(0.9).build();
        String json = MAPPER.writeValueAsString(opts);

        assertThat(json).contains("\"temperature\"");
        assertThat(json).doesNotContain("\"maxTokens\"");
        assertThat(json).doesNotContain("\"apiKey\"");
        assertThat(json).doesNotContain("\"cacheRetention\"");
        assertThat(json).doesNotContain("\"sessionId\"");
        assertThat(json).doesNotContain("\"headers\"");
        assertThat(json).doesNotContain("\"transport\"");
        assertThat(json).doesNotContain("\"maxRetryDelayMs\"");
        assertThat(json).doesNotContain("\"metadata\"");
    }

    @Test
    void streamOptions_onPayload_notSerialized() throws JsonProcessingException {
        var opts = StreamOptions.builder()
                .onPayload((payload, model) -> payload)
                .build();
        String json = MAPPER.writeValueAsString(opts);
        assertThat(json).doesNotContain("onPayload");
    }

    @Test
    void streamOptions_deserializesFromJson() throws JsonProcessingException {
        String json = """
                {
                    "temperature": 0.3,
                    "maxTokens": 1024,
                    "cacheRetention": "none",
                    "transport": "auto",
                    "maxRetryDelayMs": 5000
                }
                """;
        var opts = MAPPER.readValue(json, StreamOptions.class);
        assertThat(opts.getTemperature()).isEqualTo(0.3);
        assertThat(opts.getMaxTokens()).isEqualTo(1024);
        assertThat(opts.getCacheRetention()).isEqualTo(CacheRetention.NONE);
        assertThat(opts.getTransport()).isEqualTo(Transport.AUTO);
        assertThat(opts.getMaxRetryDelayMs()).isEqualTo(5000);
    }

    @Test
    void streamOptions_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {"temperature":0.5,"futureField":"ignored"}
                """;
        var opts = MAPPER.readValue(json, StreamOptions.class);
        assertThat(opts.getTemperature()).isEqualTo(0.5);
    }

    @Test
    void streamOptions_equals_and_hashCode() {
        var opts1 = StreamOptions.builder().temperature(0.5).maxTokens(100).build();
        var opts2 = StreamOptions.builder().temperature(0.5).maxTokens(100).build();
        var opts3 = StreamOptions.builder().temperature(0.9).maxTokens(100).build();

        assertThat(opts1).isEqualTo(opts2);
        assertThat(opts1.hashCode()).isEqualTo(opts2.hashCode());
        assertThat(opts1).isNotEqualTo(opts3);
    }

    @Test
    void streamOptions_toString_masksApiKey() {
        var opts = StreamOptions.builder().apiKey("secret-key").build();
        String str = opts.toString();
        assertThat(str).doesNotContain("secret-key");
        assertThat(str).contains("***");
    }

    // ==================== SimpleStreamOptions Builder ====================

    @Test
    void simpleStreamOptions_builder_allFields() {
        var budgets = new ThinkingBudgets(512, 1024, 4096, 8192);
        var opts = SimpleStreamOptions.simpleBuilder()
                .temperature(0.8)
                .maxTokens(8192)
                .apiKey("sk-simple")
                .cacheRetention(CacheRetention.SHORT)
                .transport(Transport.SSE)
                .reasoning(ThinkingLevel.HIGH)
                .thinkingBudgets(budgets)
                .build();

        // 继承的字段
        assertThat(opts.getTemperature()).isEqualTo(0.8);
        assertThat(opts.getMaxTokens()).isEqualTo(8192);
        assertThat(opts.getApiKey()).isEqualTo("sk-simple");
        assertThat(opts.getCacheRetention()).isEqualTo(CacheRetention.SHORT);
        assertThat(opts.getTransport()).isEqualTo(Transport.SSE);
        // 新增字段
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(opts.getThinkingBudgets()).isEqualTo(budgets);
    }

    @Test
    void simpleStreamOptions_builder_defaults() {
        var opts = SimpleStreamOptions.simpleBuilder().build();

        assertThat(opts.getTemperature()).isNull();
        assertThat(opts.getMaxTokens()).isNull();
        assertThat(opts.getReasoning()).isNull();
        assertThat(opts.getThinkingBudgets()).isNull();
    }

    @Test
    void simpleStreamOptions_jackson_roundTrip() throws JsonProcessingException {
        var budgets = new ThinkingBudgets(1024, null, 8192, null);
        var opts = SimpleStreamOptions.simpleBuilder()
                .temperature(0.6)
                .maxTokens(4096)
                .cacheRetention(CacheRetention.LONG)
                .reasoning(ThinkingLevel.MEDIUM)
                .thinkingBudgets(budgets)
                .build();

        String json = MAPPER.writeValueAsString(opts);
        var deserialized = MAPPER.readValue(json, SimpleStreamOptions.class);

        assertThat(deserialized.getTemperature()).isEqualTo(0.6);
        assertThat(deserialized.getMaxTokens()).isEqualTo(4096);
        assertThat(deserialized.getCacheRetention()).isEqualTo(CacheRetention.LONG);
        assertThat(deserialized.getReasoning()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(deserialized.getThinkingBudgets()).isEqualTo(budgets);
    }

    @Test
    void simpleStreamOptions_nullReasoningFields_omitted() throws JsonProcessingException {
        var opts = SimpleStreamOptions.simpleBuilder()
                .temperature(0.5)
                .build();
        String json = MAPPER.writeValueAsString(opts);

        assertThat(json).contains("\"temperature\"");
        assertThat(json).doesNotContain("\"reasoning\"");
        assertThat(json).doesNotContain("\"thinkingBudgets\"");
    }

    @Test
    void simpleStreamOptions_isInstanceOfStreamOptions() {
        var opts = SimpleStreamOptions.simpleBuilder()
                .reasoning(ThinkingLevel.LOW)
                .build();
        assertThat(opts).isInstanceOf(StreamOptions.class);
    }

    @Test
    void simpleStreamOptions_equals_includesReasoningFields() {
        var opts1 = SimpleStreamOptions.simpleBuilder()
                .temperature(0.5).reasoning(ThinkingLevel.HIGH).build();
        var opts2 = SimpleStreamOptions.simpleBuilder()
                .temperature(0.5).reasoning(ThinkingLevel.HIGH).build();
        var opts3 = SimpleStreamOptions.simpleBuilder()
                .temperature(0.5).reasoning(ThinkingLevel.LOW).build();

        assertThat(opts1).isEqualTo(opts2);
        assertThat(opts1.hashCode()).isEqualTo(opts2.hashCode());
        assertThat(opts1).isNotEqualTo(opts3);
    }

    @Test
    void simpleStreamOptions_deserializesFromJson() throws JsonProcessingException {
        String json = """
                {
                    "temperature": 0.7,
                    "maxTokens": 2048,
                    "reasoning": "xhigh",
                    "thinkingBudgets": {"minimal": 512, "high": 32768}
                }
                """;
        var opts = MAPPER.readValue(json, SimpleStreamOptions.class);
        assertThat(opts.getTemperature()).isEqualTo(0.7);
        assertThat(opts.getMaxTokens()).isEqualTo(2048);
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.XHIGH);
        assertThat(opts.getThinkingBudgets().minimal()).isEqualTo(512);
        assertThat(opts.getThinkingBudgets().high()).isEqualTo(32768);
        assertThat(opts.getThinkingBudgets().low()).isNull();
        assertThat(opts.getThinkingBudgets().medium()).isNull();
    }

    @Test
    void simpleStreamOptions_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {"reasoning":"medium","futureField":true}
                """;
        var opts = MAPPER.readValue(json, SimpleStreamOptions.class);
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.MEDIUM);
    }

    // ==================== PayloadInterceptor ====================

    @Test
    void payloadInterceptor_functionalInterface() {
        PayloadInterceptor interceptor = (payload, model) -> "modified";
        var model = new Model("id", "name", "api", "provider", "url",
                false, null, null, 0, 0, null, null);
        Object result = interceptor.intercept("original", model);
        assertThat(result).isEqualTo("modified");
    }

    @Test
    void payloadInterceptor_returnsNull_meansNoChange() {
        PayloadInterceptor interceptor = (payload, model) -> null;
        var model = new Model("id", "name", "api", "provider", "url",
                false, null, null, 0, 0, null, null);
        Object result = interceptor.intercept("original", model);
        assertThat(result).isNull();
    }
}
