package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：Model、ModelCost、OpenAICompletionsCompat、OpenAIResponsesCompat、
 * OpenRouterRouting、VercelGatewayRouting 类型的序列化/反序列化。
 */
class ModelTypesTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== ModelCost ====================

    @Test
    void modelCost_roundTrip() throws JsonProcessingException {
        var cost = new ModelCost(3.0, 15.0, 0.3, 3.75);
        String json = MAPPER.writeValueAsString(cost);
        var deserialized = MAPPER.readValue(json, ModelCost.class);
        assertThat(deserialized).isEqualTo(cost);
    }

    @Test
    void modelCost_jsonFieldNames() throws JsonProcessingException {
        var cost = new ModelCost(3.0, 15.0, 0.3, 3.75);
        String json = MAPPER.writeValueAsString(cost);
        assertThat(json).contains("\"input\"");
        assertThat(json).contains("\"output\"");
        assertThat(json).contains("\"cacheRead\"");
        assertThat(json).contains("\"cacheWrite\"");
    }

    @Test
    void modelCost_zeroPricing() throws JsonProcessingException {
        var cost = new ModelCost(0.0, 0.0, 0.0, 0.0);
        String json = MAPPER.writeValueAsString(cost);
        var deserialized = MAPPER.readValue(json, ModelCost.class);
        assertThat(deserialized).isEqualTo(cost);
    }

    // ==================== OpenAICompletionsCompat ====================

    @Test
    void openAICompletionsCompat_allFieldsNull_roundTrip() throws JsonProcessingException {
        var compat = new OpenAICompletionsCompat(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        String json = MAPPER.writeValueAsString(compat);
        // NON_NULL 配置下，所有 null 字段应被省略
        assertThat(json).isEqualTo("{}");
        var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
        assertThat(deserialized).isEqualTo(compat);
    }

    @Test
    void openAICompletionsCompat_withBooleanFields_roundTrip() throws JsonProcessingException {
        var compat = new OpenAICompletionsCompat(
                true, true, false, null, true, "max_completion_tokens",
                false, false, true, "openai", null, null, true);
        String json = MAPPER.writeValueAsString(compat);
        var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
        assertThat(deserialized).isEqualTo(compat);
    }

    @Test
    void openAICompletionsCompat_withReasoningEffortMap_roundTrip() throws JsonProcessingException {
        var map = Map.of(ThinkingLevel.LOW, "low", ThinkingLevel.HIGH, "high");
        var compat = new OpenAICompletionsCompat(
                null, null, true, map, null, null, null, null, null, null, null, null, null);
        String json = MAPPER.writeValueAsString(compat);
        var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
        assertThat(deserialized.reasoningEffortMap()).containsEntry(ThinkingLevel.LOW, "low");
        assertThat(deserialized.reasoningEffortMap()).containsEntry(ThinkingLevel.HIGH, "high");
    }

    @Test
    void openAICompletionsCompat_withRouting_roundTrip() throws JsonProcessingException {
        var orRouting = new OpenRouterRouting(List.of("anthropic"), List.of("anthropic", "openai"));
        var vRouting = new VercelGatewayRouting(List.of("bedrock"), null);
        var compat = new OpenAICompletionsCompat(
                null, null, null, null, null, null, null, null, null, null,
                orRouting, vRouting, null);
        String json = MAPPER.writeValueAsString(compat);
        var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
        assertThat(deserialized.openRouterRouting().only()).containsExactly("anthropic");
        assertThat(deserialized.openRouterRouting().order()).containsExactly("anthropic", "openai");
        
        assertThat(deserialized.vercelGatewayRouting().only()).containsExactly("bedrock");
        assertThat(deserialized.vercelGatewayRouting().order()).isNull();
    }

    @Test
    void openAICompletionsCompat_thinkingFormatValues() throws JsonProcessingException {
        for (String format : List.of("openai", "zai", "qwen", "qwen-chat-template")) {
            var compat = new OpenAICompletionsCompat(
                    null, null, null, null, null, null, null, null, null, format, null, null, null);
            String json = MAPPER.writeValueAsString(compat);
            var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
            assertThat(deserialized.thinkingFormat()).isEqualTo(format);
        }
    }

    @Test
    void openAICompletionsCompat_maxTokensFieldValues() throws JsonProcessingException {
        for (String field : List.of("max_completion_tokens", "max_tokens")) {
            var compat = new OpenAICompletionsCompat(
                    null, null, null, null, null, field, null, null, null, null, null, null, null);
            String json = MAPPER.writeValueAsString(compat);
            var deserialized = MAPPER.readValue(json, OpenAICompletionsCompat.class);
            assertThat(deserialized.maxTokensField()).isEqualTo(field);
        }
    }

    // ==================== OpenAIResponsesCompat ====================

    @Test
    void openAIResponsesCompat_emptyRecord_roundTrip() throws JsonProcessingException {
        var compat = new OpenAIResponsesCompat();
        String json = MAPPER.writeValueAsString(compat);
        assertThat(json).isEqualTo("{}");
        var deserialized = MAPPER.readValue(json, OpenAIResponsesCompat.class);
        assertThat(deserialized).isEqualTo(compat);
    }

    // ==================== OpenRouterRouting ====================

    @Test
    void openRouterRouting_roundTrip() throws JsonProcessingException {
        var routing = new OpenRouterRouting(List.of("amazon-bedrock", "anthropic"), List.of("anthropic", "openai"));
        String json = MAPPER.writeValueAsString(routing);
        var deserialized = MAPPER.readValue(json, OpenRouterRouting.class);
        assertThat(deserialized).isEqualTo(routing);
    }

    @Test
    void openRouterRouting_nullFields_omitted() throws JsonProcessingException {
        var routing = new OpenRouterRouting(null, null);
        String json = MAPPER.writeValueAsString(routing);
        assertThat(json).isEqualTo("{}");
    }

    // ==================== VercelGatewayRouting ====================

    @Test
    void vercelGatewayRouting_roundTrip() throws JsonProcessingException {
        var routing = new VercelGatewayRouting(List.of("bedrock"), List.of("anthropic"));
        String json = MAPPER.writeValueAsString(routing);
        var deserialized = MAPPER.readValue(json, VercelGatewayRouting.class);
        assertThat(deserialized).isEqualTo(routing);
    }

    // ==================== Model ====================

    @Test
    void model_fullFields_roundTrip() throws JsonProcessingException {
        var cost = new ModelCost(3.0, 15.0, 0.3, 3.75);
        var compat = new OpenAICompletionsCompat(
                true, true, false, null, true, "max_completion_tokens",
                false, false, false, "openai", null, null, true);
        var model = new Model(
                "gpt-4o", "GPT-4o", "openai-completions", "openai",
                "https://api.openai.com/v1", false,
                List.of("text", "image"), cost, 128000, 16384,
                Map.of("X-Custom", "value"), compat);
        String json = MAPPER.writeValueAsString(model);
        var deserialized = MAPPER.readValue(json, Model.class);
        assertThat(deserialized).isEqualTo(model);
    }

    @Test
    void model_nullOptionalFields_roundTrip() throws JsonProcessingException {
        var cost = new ModelCost(3.0, 15.0, 0.3, 3.75);
        var model = new Model(
                "claude-sonnet-4-20250514", "Claude Sonnet 4",
                "anthropic-messages", "anthropic",
                "https://api.anthropic.com/v1", true,
                List.of("text", "image"), cost, 200000, 64000,
                null, null);
        String json = MAPPER.writeValueAsString(model);
        // headers 和 compat 为 null，应被省略
        assertThat(json).doesNotContain("\"headers\"");
        assertThat(json).doesNotContain("\"compat\"");
        var deserialized = MAPPER.readValue(json, Model.class);
        assertThat(deserialized).isEqualTo(model);
    }

    @Test
    void model_textOnlyInput() throws JsonProcessingException {
        var cost = new ModelCost(0.5, 1.5, 0.0, 0.0);
        var model = new Model(
                "mistral-large", "Mistral Large",
                "mistral-conversations", "mistral",
                "https://api.mistral.ai/v1", false,
                List.of("text"), cost, 128000, 8192,
                null, null);
        String json = MAPPER.writeValueAsString(model);
        var deserialized = MAPPER.readValue(json, Model.class);
        assertThat(deserialized.input()).containsExactly("text");
    }

    @Test
    void model_withCustomHeaders() throws JsonProcessingException {
        var cost = new ModelCost(0.0, 0.0, 0.0, 0.0);
        var headers = Map.of("Authorization", "Bearer token", "X-Custom-Header", "custom-value");
        var model = new Model(
                "custom-model", "Custom Model",
                "openai-completions", "openrouter",
                "https://openrouter.ai/api/v1", false,
                List.of("text"), cost, 32000, 4096,
                headers, null);
        String json = MAPPER.writeValueAsString(model);
        var deserialized = MAPPER.readValue(json, Model.class);
        assertThat(deserialized.headers()).containsEntry("Authorization", "Bearer token");
        assertThat(deserialized.headers()).containsEntry("X-Custom-Header", "custom-value");
    }

    @Test
    void model_jsonFieldNames() throws JsonProcessingException {
        var cost = new ModelCost(1.0, 2.0, 0.1, 0.2);
        var model = new Model(
                "test-id", "Test Name", "openai-completions", "openai",
                "https://api.openai.com/v1", true,
                List.of("text", "image"), cost, 128000, 16384,
                null, null);
        String json = MAPPER.writeValueAsString(model);
        assertThat(json).contains("\"id\"");
        assertThat(json).contains("\"name\"");
        assertThat(json).contains("\"api\"");
        assertThat(json).contains("\"provider\"");
        assertThat(json).contains("\"baseUrl\"");
        assertThat(json).contains("\"reasoning\"");
        assertThat(json).contains("\"input\"");
        assertThat(json).contains("\"cost\"");
        assertThat(json).contains("\"contextWindow\"");
        assertThat(json).contains("\"maxTokens\"");
    }

    @Test
    void model_ignoresUnknownFields() throws JsonProcessingException {
        // 模拟未来新增字段的 JSON
        String json = """
                {
                    "id": "test-model",
                    "name": "Test Model",
                    "api": "openai-completions",
                    "provider": "openai",
                    "baseUrl": "https://api.openai.com/v1",
                    "reasoning": false,
                    "input": ["text"],
                    "cost": {"input": 1.0, "output": 2.0, "cacheRead": 0.1, "cacheWrite": 0.2},
                    "contextWindow": 128000,
                    "maxTokens": 16384,
                    "futureField": "should be ignored",
                    "anotherNewField": 42
                }
                """;
        var model = MAPPER.readValue(json, Model.class);
        assertThat(model.id()).isEqualTo("test-model");
        assertThat(model.api()).isEqualTo("openai-completions");
    }

    @Test
    void model_withFullCompat_roundTrip() throws JsonProcessingException {
        var reasoningMap = Map.of(
                ThinkingLevel.LOW, "low",
                ThinkingLevel.MEDIUM, "medium",
                ThinkingLevel.HIGH, "high");
        var orRouting = new OpenRouterRouting(List.of("anthropic"), null);
        var compat = new OpenAICompletionsCompat(
                true, true, true, reasoningMap, true,
                "max_completion_tokens", true, true, false,
                "openai", orRouting, null, true);
        var cost = new ModelCost(2.5, 10.0, 0.25, 2.5);
        var model = new Model(
                "o3-mini", "o3-mini", "openai-completions", "openai",
                "https://api.openai.com/v1", true,
                List.of("text"), cost, 200000, 100000,
                null, compat);
        String json = MAPPER.writeValueAsString(model);
        var deserialized = MAPPER.readValue(json, Model.class);
        assertThat(deserialized).isEqualTo(model);
        assertThat(deserialized.compat().supportsReasoningEffort()).isTrue();
        assertThat(deserialized.compat().reasoningEffortMap()).containsEntry(ThinkingLevel.HIGH, "high");
    }
}
