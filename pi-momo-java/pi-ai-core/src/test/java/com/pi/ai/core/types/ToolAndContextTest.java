package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool 和 Context 类型的单元测试。
 *
 * <p>验证 Jackson 序列化/反序列化、便捷构造方法和 Builder 模式。
 */
class ToolAndContextTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== Tool 测试 ====================

    @Test
    void tool_serializesAllFields() throws JsonProcessingException {
        JsonNode params = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
        var tool = new Tool("search", "搜索工具", params);

        String json = MAPPER.writeValueAsString(tool);
        assertThat(json).contains("\"name\":\"search\"");
        assertThat(json).contains("\"description\":\"搜索工具\"");
        assertThat(json).contains("\"parameters\"");
        assertThat(json).contains("\"type\":\"object\"");
    }

    @Test
    void tool_roundTrip() throws JsonProcessingException {
        JsonNode params = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"]}");
        var original = new Tool("calculate", "计算工具", params);

        String json = MAPPER.writeValueAsString(original);
        Tool deserialized = MAPPER.readValue(json, Tool.class);

        assertThat(deserialized.name()).isEqualTo("calculate");
        assertThat(deserialized.description()).isEqualTo("计算工具");
        assertThat(deserialized.parameters()).isEqualTo(params);
    }

    @Test
    void tool_nullParametersOmittedInJson() throws JsonProcessingException {
        var tool = new Tool("simple", "简单工具", null);
        String json = MAPPER.writeValueAsString(tool);
        assertThat(json).doesNotContain("parameters");
    }

    @Test
    void tool_deserializesFromJson() throws JsonProcessingException {
        String json = """
                {"name":"greet","description":"打招呼","parameters":{"type":"object","properties":{"name":{"type":"string"}}}}
                """;
        Tool tool = MAPPER.readValue(json, Tool.class);
        assertThat(tool.name()).isEqualTo("greet");
        assertThat(tool.description()).isEqualTo("打招呼");
        assertThat(tool.parameters().get("type").asText()).isEqualTo("object");
    }

    @Test
    void tool_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {"name":"test","description":"desc","parameters":{},"unknownField":"value"}
                """;
        Tool tool = MAPPER.readValue(json, Tool.class);
        assertThat(tool.name()).isEqualTo("test");
    }

    // ==================== Context 测试 ====================

    @Test
    void context_fullConstructor_roundTrip() throws JsonProcessingException {
        JsonNode params = MAPPER.readTree("{\"type\":\"object\"}");
        var tool = new Tool("search", "搜索", params);
        var msg = new UserMessage("user", "你好", System.currentTimeMillis());
        var original = new Context("你是一个助手", List.of(msg), List.of(tool));

        String json = MAPPER.writeValueAsString(original);
        Context deserialized = MAPPER.readValue(json, Context.class);

        assertThat(deserialized.systemPrompt()).isEqualTo("你是一个助手");
        assertThat(deserialized.messages()).hasSize(1);
        assertThat(deserialized.tools()).hasSize(1);
        assertThat(deserialized.tools().get(0).name()).isEqualTo("search");
    }

    @Test
    void context_nullFieldsOmittedInJson() throws JsonProcessingException {
        var msg = new UserMessage("user", "hello", 1000L);
        var ctx = new Context(null, List.of(msg), null);

        String json = MAPPER.writeValueAsString(ctx);
        assertThat(json).doesNotContain("systemPrompt");
        assertThat(json).doesNotContain("tools");
        assertThat(json).contains("\"messages\"");
    }

    @Test
    void context_messagesOnlyConstructor() {
        var msg = new UserMessage("user", "hi", 1000L);
        var ctx = new Context(List.of(msg));

        assertThat(ctx.systemPrompt()).isNull();
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.tools()).isNull();
    }

    @Test
    void context_systemPromptAndMessagesConstructor() {
        var msg = new UserMessage("user", "hi", 1000L);
        var ctx = new Context("system prompt", List.of(msg));

        assertThat(ctx.systemPrompt()).isEqualTo("system prompt");
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.tools()).isNull();
    }

    @Test
    void context_ofFactoryMethod_allFields() {
        var msg = new UserMessage("user", "hi", 1000L);
        var tool = new Tool("t", "d", null);
        var ctx = Context.of("prompt", List.of(msg), List.of(tool));

        assertThat(ctx.systemPrompt()).isEqualTo("prompt");
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.tools()).hasSize(1);
    }

    @Test
    void context_ofFactoryMethod_messagesOnly() {
        var msg = new UserMessage("user", "hi", 1000L);
        var ctx = Context.of(List.of(msg));

        assertThat(ctx.systemPrompt()).isNull();
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.tools()).isNull();
    }

    @Test
    void context_builder_allFields() throws JsonProcessingException {
        JsonNode params = MAPPER.readTree("{\"type\":\"object\"}");
        var tool = new Tool("calc", "计算", params);
        var msg = new UserMessage("user", "1+1=?", 2000L);

        var ctx = Context.builder()
                .systemPrompt("你是计算器")
                .messages(List.of(msg))
                .tools(List.of(tool))
                .build();

        assertThat(ctx.systemPrompt()).isEqualTo("你是计算器");
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.tools()).hasSize(1);
    }

    @Test
    void context_builder_defaults() {
        var ctx = Context.builder().build();

        assertThat(ctx.systemPrompt()).isNull();
        assertThat(ctx.messages()).isEmpty();
        assertThat(ctx.tools()).isNull();
    }

    @Test
    void context_builder_roundTrip() throws JsonProcessingException {
        var msg = new UserMessage("user", "test", 3000L);
        var original = Context.builder()
                .systemPrompt("sys")
                .messages(List.of(msg))
                .build();

        String json = MAPPER.writeValueAsString(original);
        Context deserialized = MAPPER.readValue(json, Context.class);

        assertThat(deserialized.systemPrompt()).isEqualTo("sys");
        assertThat(deserialized.messages()).hasSize(1);
        assertThat(deserialized.tools()).isNull();
    }

    @Test
    void context_deserializesFromJson() throws JsonProcessingException {
        String json = """
                {
                  "systemPrompt": "你是助手",
                  "messages": [
                    {"role": "user", "content": "你好", "timestamp": 1000}
                  ],
                  "tools": [
                    {"name": "search", "description": "搜索", "parameters": {"type": "object"}}
                  ]
                }
                """;
        Context ctx = MAPPER.readValue(json, Context.class);
        assertThat(ctx.systemPrompt()).isEqualTo("你是助手");
        assertThat(ctx.messages()).hasSize(1);
        assertThat(ctx.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(ctx.tools()).hasSize(1);
        assertThat(ctx.tools().get(0).name()).isEqualTo("search");
    }

    @Test
    void context_ignoresUnknownFields() throws JsonProcessingException {
        String json = """
                {"messages":[],"unknownField":"value"}
                """;
        Context ctx = MAPPER.readValue(json, Context.class);
        assertThat(ctx.messages()).isEmpty();
    }

    @Test
    void context_withAssistantMessage() throws JsonProcessingException {
        // 验证 Context 可以包含多态 Message 类型
        var userMsg = new UserMessage("user", "hello", 1000L);
        var assistantMsg = AssistantMessage.builder()
                .content(List.of(new TextContent("world")))
                .api("openai-completions")
                .provider("openai")
                .model("gpt-4")
                .usage(new Usage(10, 20, 0, 0, 30, null))
                .stopReason(StopReason.STOP)
                .timestamp(2000L)
                .build();

        var ctx = new Context("sys", List.of(userMsg, assistantMsg), null);
        String json = MAPPER.writeValueAsString(ctx);
        Context deserialized = MAPPER.readValue(json, Context.class);

        assertThat(deserialized.messages()).hasSize(2);
        assertThat(deserialized.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(deserialized.messages().get(1)).isInstanceOf(AssistantMessage.class);
    }
}
