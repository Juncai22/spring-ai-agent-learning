package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Message type hierarchy Jackson serialization and deserialization.
 */
class MessageSerializationTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== UserMessage ====================

    @Test
    void userMessage_plainText_serializes() throws JsonProcessingException {
        var msg = new UserMessage("user", "Hello world", 1700000000000L);
        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"role\":\"user\"");
        assertThat(json).contains("\"content\":\"Hello world\"");
        assertThat(json).contains("\"timestamp\":1700000000000");
    }

    @Test
    void userMessage_plainText_roundTrip() throws JsonProcessingException {
        var original = new UserMessage("user", "Hello world", 1700000000000L);
        String json = MAPPER.writeValueAsString(original);
        UserMessage deserialized = MAPPER.readValue(json, UserMessage.class);
        assertThat(deserialized.role()).isEqualTo("user");
        assertThat(deserialized.content()).isEqualTo("Hello world");
        assertThat(deserialized.timestamp()).isEqualTo(1700000000000L);
    }

    @Test
    void userMessage_contentBlocks_serializes() throws JsonProcessingException {
        List<UserContentBlock> blocks = List.of(
            new TextContent("hello"),
            new ImageContent("base64data", "image/png")
        );
        var msg = new UserMessage(blocks, 1700000000000L);
        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"role\":\"user\"");
        assertThat(json).contains("\"type\":\"text\"");
        assertThat(json).contains("\"type\":\"image\"");
    }

    @Test
    void userMessage_convenienceConstructor_plainText() {
        var msg = new UserMessage("Hello", 123L);
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.content()).isEqualTo("Hello");
        assertThat(msg.timestamp()).isEqualTo(123L);
    }

    @Test
    void userMessage_convenienceConstructor_blocks() {
        List<UserContentBlock> blocks = List.of(new TextContent("hi"));
        var msg = new UserMessage(blocks, 456L);
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.content()).isEqualTo(blocks);
        assertThat(msg.timestamp()).isEqualTo(456L);
    }

    @Test
    void userMessage_deserializesAsMessage() throws JsonProcessingException {
        String json = "{\"role\":\"user\",\"content\":\"hello\",\"timestamp\":100}";
        Message msg = MAPPER.readValue(json, Message.class);
        assertThat(msg).isInstanceOf(UserMessage.class);
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.timestamp()).isEqualTo(100L);
    }

    @Test
    void userMessage_withArrayContent_deserializesAsMessage() throws JsonProcessingException {
        String json = "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"timestamp\":200}";
        Message msg = MAPPER.readValue(json, Message.class);
        assertThat(msg).isInstanceOf(UserMessage.class);
        UserMessage um = (UserMessage) msg;
        assertThat(um.content()).isInstanceOf(List.class);
    }

    // ==================== AssistantMessage ====================

    @Test
    void assistantMessage_builder_createsCorrectly() {
        var usage = new Usage(100, 50, 10, 5, 165,
            new Usage.Cost(0.01, 0.02, 0.001, 0.0005, 0.0315));
        var msg = AssistantMessage.builder()
            .content(List.of(new TextContent("Hello!")))
            .api("anthropic-messages")
            .provider("anthropic")
            .model("claude-3-opus")
            .usage(usage)
            .stopReason(StopReason.STOP)
            .timestamp(1700000000000L)
            .build();

        assertThat(msg.role()).isEqualTo("assistant");
        assertThat(msg.getContent()).hasSize(1);
        assertThat(msg.getApi()).isEqualTo("anthropic-messages");
        assertThat(msg.getProvider()).isEqualTo("anthropic");
        assertThat(msg.getModel()).isEqualTo("claude-3-opus");
        assertThat(msg.getUsage()).isEqualTo(usage);
        assertThat(msg.getStopReason()).isEqualTo(StopReason.STOP);
        assertThat(msg.getErrorMessage()).isNull();
        assertThat(msg.timestamp()).isEqualTo(1700000000000L);
    }

    @Test
    void assistantMessage_serializes() throws JsonProcessingException {
        var msg = AssistantMessage.builder()
            .content(List.of(new TextContent("hi")))
            .api("openai-completions")
            .provider("openai")
            .model("gpt-4")
            .usage(new Usage(10, 5, 0, 0, 15, new Usage.Cost(0, 0, 0, 0, 0)))
            .stopReason(StopReason.STOP)
            .timestamp(100L)
            .build();

        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"role\":\"assistant\"");
        assertThat(json).contains("\"api\":\"openai-completions\"");
        assertThat(json).contains("\"provider\":\"openai\"");
        assertThat(json).contains("\"model\":\"gpt-4\"");
        assertThat(json).contains("\"stopReason\":\"stop\"");
        // errorMessage is null, should be omitted
        assertThat(json).doesNotContain("errorMessage");
    }

    @Test
    void assistantMessage_withError_serializes() throws JsonProcessingException {
        var msg = AssistantMessage.builder()
            .content(List.of())
            .api("anthropic-messages")
            .provider("anthropic")
            .model("claude-3")
            .usage(new Usage(0, 0, 0, 0, 0, null))
            .stopReason(StopReason.ERROR)
            .errorMessage("Rate limit exceeded")
            .timestamp(200L)
            .build();

        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"errorMessage\":\"Rate limit exceeded\"");
        assertThat(json).contains("\"stopReason\":\"error\"");
    }

    @Test
    void assistantMessage_roundTrip() throws JsonProcessingException {
        var usage = new Usage(100, 50, 10, 5, 165,
            new Usage.Cost(0.01, 0.02, 0.001, 0.0005, 0.0315));
        var original = AssistantMessage.builder()
            .content(List.of(new TextContent("text", "response", null)))
            .api("anthropic-messages")
            .provider("anthropic")
            .model("claude-3-opus")
            .usage(usage)
            .stopReason(StopReason.STOP)
            .timestamp(1700000000000L)
            .build();

        String json = MAPPER.writeValueAsString(original);
        AssistantMessage deserialized = MAPPER.readValue(json, AssistantMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void assistantMessage_deserializesAsMessage() throws JsonProcessingException {
        String json = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]," +
            "\"api\":\"openai-completions\",\"provider\":\"openai\",\"model\":\"gpt-4\"," +
            "\"usage\":{\"input\":10,\"output\":5,\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":15," +
            "\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}}," +
            "\"stopReason\":\"stop\",\"timestamp\":100}";
        Message msg = MAPPER.readValue(json, Message.class);
        assertThat(msg).isInstanceOf(AssistantMessage.class);
        assertThat(msg.role()).isEqualTo("assistant");
    }

    @Test
    void assistantMessage_mutability_forStreaming() {
        var msg = AssistantMessage.builder()
            .content(new java.util.ArrayList<>(List.of(new TextContent("partial"))))
            .api("anthropic-messages")
            .provider("anthropic")
            .model("claude-3")
            .stopReason(StopReason.STOP)
            .timestamp(100L)
            .build();

        // Simulate streaming mutation
        msg.setUsage(new Usage(50, 25, 0, 0, 75, null));
        assertThat(msg.getUsage().input()).isEqualTo(50);

        msg.setStopReason(StopReason.TOOL_USE);
        assertThat(msg.getStopReason()).isEqualTo(StopReason.TOOL_USE);

        msg.setErrorMessage("something went wrong");
        assertThat(msg.getErrorMessage()).isEqualTo("something went wrong");

        msg.setContent(List.of(new TextContent("updated")));
        assertThat(msg.getContent()).hasSize(1);
    }

    @Test
    void assistantMessage_equals_hashCode() {
        var usage = new Usage(10, 5, 0, 0, 15, new Usage.Cost(0, 0, 0, 0, 0));
        var msg1 = AssistantMessage.builder()
            .content(List.of(new TextContent("text", "hi", null)))
            .api("openai-completions").provider("openai").model("gpt-4")
            .usage(usage).stopReason(StopReason.STOP).timestamp(100L).build();
        var msg2 = AssistantMessage.builder()
            .content(List.of(new TextContent("text", "hi", null)))
            .api("openai-completions").provider("openai").model("gpt-4")
            .usage(usage).stopReason(StopReason.STOP).timestamp(100L).build();

        assertThat(msg1).isEqualTo(msg2);
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());
    }

    @Test
    void assistantMessage_notEquals_differentContent() {
        var usage = new Usage(10, 5, 0, 0, 15, null);
        var msg1 = AssistantMessage.builder()
            .content(List.of(new TextContent("text", "a", null)))
            .api("a").provider("p").model("m").usage(usage)
            .stopReason(StopReason.STOP).timestamp(100L).build();
        var msg2 = AssistantMessage.builder()
            .content(List.of(new TextContent("text", "b", null)))
            .api("a").provider("p").model("m").usage(usage)
            .stopReason(StopReason.STOP).timestamp(100L).build();

        assertThat(msg1).isNotEqualTo(msg2);
    }

    @Test
    void assistantMessage_ignoresUnknownFields() throws JsonProcessingException {
        String json = "{\"role\":\"assistant\",\"content\":[],\"api\":\"a\",\"provider\":\"p\"," +
            "\"model\":\"m\",\"usage\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0," +
            "\"totalTokens\":0},\"stopReason\":\"stop\",\"timestamp\":0,\"unknownField\":\"ignored\"}";
        AssistantMessage msg = MAPPER.readValue(json, AssistantMessage.class);
        assertThat(msg.getApi()).isEqualTo("a");
    }

    // ==================== ToolResultMessage ====================

    @Test
    void toolResultMessage_serializes() throws JsonProcessingException {
        var msg = new ToolResultMessage("toolResult", "call-1", "search",
            List.of(new TextContent("result text")), null, false, 1700000000000L);
        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"role\":\"toolResult\"");
        assertThat(json).contains("\"toolCallId\":\"call-1\"");
        assertThat(json).contains("\"toolName\":\"search\"");
        assertThat(json).contains("\"isError\":false");
        // null details should be omitted
        assertThat(json).doesNotContain("\"details\"");
    }

    @Test
    void toolResultMessage_withDetails_serializes() throws JsonProcessingException {
        var msg = new ToolResultMessage("toolResult", "call-2", "calc",
            List.of(new TextContent("42")), Map.of("elapsed", 100), false, 100L);
        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"details\"");
        assertThat(json).contains("\"elapsed\"");
    }

    @Test
    void toolResultMessage_withError_serializes() throws JsonProcessingException {
        var msg = new ToolResultMessage("call-3", "failing-tool",
            List.of(new TextContent("Error: not found")), null, true, 300L);
        String json = MAPPER.writeValueAsString(msg);
        assertThat(json).contains("\"isError\":true");
    }

    @Test
    void toolResultMessage_roundTrip() throws JsonProcessingException {
        var original = new ToolResultMessage("toolResult", "call-1", "search",
            List.of(new TextContent("text", "found it", null)), null, false, 1700000000000L);
        String json = MAPPER.writeValueAsString(original);
        ToolResultMessage deserialized = MAPPER.readValue(json, ToolResultMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void toolResultMessage_convenienceConstructor() {
        var msg = new ToolResultMessage("id-1", "tool", List.of(), null, false, 100L);
        assertThat(msg.role()).isEqualTo("toolResult");
        assertThat(msg.toolCallId()).isEqualTo("id-1");
        assertThat(msg.toolName()).isEqualTo("tool");
    }

    @Test
    void toolResultMessage_deserializesAsMessage() throws JsonProcessingException {
        String json = "{\"role\":\"toolResult\",\"toolCallId\":\"c1\",\"toolName\":\"t\"," +
            "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false,\"timestamp\":100}";
        Message msg = MAPPER.readValue(json, Message.class);
        assertThat(msg).isInstanceOf(ToolResultMessage.class);
        assertThat(msg.role()).isEqualTo("toolResult");
    }

    // ==================== Polymorphic Message deserialization ====================

    @Test
    void message_polymorphicDeserialization_allTypes() throws JsonProcessingException {
        String userJson = "{\"role\":\"user\",\"content\":\"hi\",\"timestamp\":1}";
        String assistantJson = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]," +
            "\"api\":\"a\",\"provider\":\"p\",\"model\":\"m\"," +
            "\"usage\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":0}," +
            "\"stopReason\":\"stop\",\"timestamp\":2}";
        String toolResultJson = "{\"role\":\"toolResult\",\"toolCallId\":\"c1\",\"toolName\":\"t\"," +
            "\"content\":[],\"isError\":false,\"timestamp\":3}";

        Message user = MAPPER.readValue(userJson, Message.class);
        Message assistant = MAPPER.readValue(assistantJson, Message.class);
        Message toolResult = MAPPER.readValue(toolResultJson, Message.class);

        assertThat(user).isInstanceOf(UserMessage.class);
        assertThat(assistant).isInstanceOf(AssistantMessage.class);
        assertThat(toolResult).isInstanceOf(ToolResultMessage.class);
    }

    @Test
    void message_list_roundTrip() throws JsonProcessingException {
        List<Message> messages = List.of(
            new UserMessage("user", "What is 2+2?", 1L),
            AssistantMessage.builder()
                .content(List.of(new TextContent("text", "4", null)))
                .api("openai-completions").provider("openai").model("gpt-4")
                .usage(new Usage(5, 3, 0, 0, 8, new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP).timestamp(2L).build(),
            new ToolResultMessage("toolResult", "c1", "calc",
                List.of(new TextContent("text", "4", null)), null, false, 3L)
        );

        String json = MAPPER.writeValueAsString(messages);
        List<Message> deserialized = MAPPER.readValue(json, new TypeReference<List<Message>>() {});

        assertThat(deserialized).hasSize(3);
        assertThat(deserialized.get(0)).isInstanceOf(UserMessage.class);
        assertThat(deserialized.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(deserialized.get(2)).isInstanceOf(ToolResultMessage.class);
    }
}
