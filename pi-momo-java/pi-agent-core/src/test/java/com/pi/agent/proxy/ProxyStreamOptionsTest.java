package com.pi.agent.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.ThinkingLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProxyStreamOptions 单元测试。
 */
class ProxyStreamOptionsTest {

    private final ObjectMapper mapper = PiAiJson.MAPPER;

    @Test
    void builderSetsAllFields() {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
            .authToken("my-token")
            .proxyUrl("https://proxy.example.com")
            .temperature(0.7)
            .maxTokens(4096)
            .apiKey("api-key-123")
            .reasoning(ThinkingLevel.HIGH)
            .thinkingBudgets(new ThinkingBudgets(null, null, null, 32768))
            .build();

        assertThat(opts.getAuthToken()).isEqualTo("my-token");
        assertThat(opts.getProxyUrl()).isEqualTo("https://proxy.example.com");
        assertThat(opts.getTemperature()).isEqualTo(0.7);
        assertThat(opts.getMaxTokens()).isEqualTo(4096);
        assertThat(opts.getApiKey()).isEqualTo("api-key-123");
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(opts.getThinkingBudgets()).isNotNull();
    }

    @Test
    void builderDefaultsToNull() {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder().build();

        assertThat(opts.getAuthToken()).isNull();
        assertThat(opts.getProxyUrl()).isNull();
        assertThat(opts.getTemperature()).isNull();
        assertThat(opts.getMaxTokens()).isNull();
        assertThat(opts.getReasoning()).isNull();
        assertThat(opts.getThinkingBudgets()).isNull();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
            .authToken("token-abc")
            .proxyUrl("https://proxy.test.com")
            .temperature(0.5)
            .maxTokens(2048)
            .reasoning(ThinkingLevel.MEDIUM)
            .headers(Map.of("X-Custom", "value"))
            .build();

        String json = mapper.writeValueAsString(opts);
        ProxyStreamOptions deserialized = mapper.readValue(json, ProxyStreamOptions.class);

        assertThat(deserialized.getAuthToken()).isEqualTo("token-abc");
        assertThat(deserialized.getProxyUrl()).isEqualTo("https://proxy.test.com");
        assertThat(deserialized.getTemperature()).isEqualTo(0.5);
        assertThat(deserialized.getMaxTokens()).isEqualTo(2048);
        assertThat(deserialized.getReasoning()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(deserialized.getHeaders()).containsEntry("X-Custom", "value");
    }

    @Test
    void equalsAndHashCode() {
        ProxyStreamOptions opts1 = ProxyStreamOptions.proxyBuilder()
            .authToken("token")
            .proxyUrl("https://proxy.com")
            .temperature(0.5)
            .build();

        ProxyStreamOptions opts2 = ProxyStreamOptions.proxyBuilder()
            .authToken("token")
            .proxyUrl("https://proxy.com")
            .temperature(0.5)
            .build();

        ProxyStreamOptions opts3 = ProxyStreamOptions.proxyBuilder()
            .authToken("different-token")
            .proxyUrl("https://proxy.com")
            .temperature(0.5)
            .build();

        assertThat(opts1).isEqualTo(opts2);
        assertThat(opts1.hashCode()).isEqualTo(opts2.hashCode());
        assertThat(opts1).isNotEqualTo(opts3);
    }

    @Test
    void toStringMasksAuthToken() {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
            .authToken("secret-token")
            .proxyUrl("https://proxy.com")
            .build();

        String str = opts.toString();
        assertThat(str).contains("***");
        assertThat(str).doesNotContain("secret-token");
        assertThat(str).contains("https://proxy.com");
    }

    @Test
    void nullFieldsExcludedFromJson() throws Exception {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
            .authToken("token")
            .build();

        String json = mapper.writeValueAsString(opts);
        assertThat(json).contains("authToken");
        assertThat(json).doesNotContain("proxyUrl");
        assertThat(json).doesNotContain("temperature");
        assertThat(json).doesNotContain("reasoning");
    }

    @Test
    void inheritsStreamOptionsFields() {
        ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
            .authToken("token")
            .proxyUrl("https://proxy.com")
            .sessionId("session-1")
            .maxRetryDelayMs(5000)
            .metadata(Map.of("key", "value"))
            .build();

        assertThat(opts.getSessionId()).isEqualTo("session-1");
        assertThat(opts.getMaxRetryDelayMs()).isEqualTo(5000);
        assertThat(opts.getMetadata()).containsEntry("key", "value");
    }
}
