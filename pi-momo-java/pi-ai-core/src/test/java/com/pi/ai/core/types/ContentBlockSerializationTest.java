package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ContentBlock type hierarchy Jackson serialization and deserialization.
 */
class ContentBlockSerializationTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // --- TextContent ---

    @Test
    void textContent_serializesWithTypeField() throws JsonProcessingException {
        var tc = new TextContent("text", "hello world", null);
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"type\":\"text\"");
        assertThat(json).contains("\"text\":\"hello world\"");
        // null textSignature should be omitted (NON_NULL)
        assertThat(json).doesNotContain("textSignature");
    }

    @Test
    void textContent_withSignature_serializes() throws JsonProcessingException {
        var tc = new TextContent("text", "hello", "sig-123");
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"textSignature\":\"sig-123\"");
    }

    @Test
    void textContent_roundTrip() throws JsonProcessingException {
        var original = new TextContent("text", "hello world", "sig-abc");
        String json = MAPPER.writeValueAsString(original);
        TextContent deserialized = MAPPER.readValue(json, TextContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void textContent_convenienceConstructor() {
        var tc = new TextContent("hello");
        assertThat(tc.type()).isEqualTo("text");
        assertThat(tc.text()).isEqualTo("hello");
        assertThat(tc.textSignature()).isNull();
    }

    @Test
    void textContent_deserializesAsContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"text\",\"text\":\"hello\"}";
        ContentBlock block = MAPPER.readValue(json, ContentBlock.class);
        assertThat(block).isInstanceOf(TextContent.class);
        assertThat(((TextContent) block).text()).isEqualTo("hello");
    }

    @Test
    void textContent_deserializesAsUserContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"text\",\"text\":\"hello\"}";
        UserContentBlock block = MAPPER.readValue(json, UserContentBlock.class);
        assertThat(block).isInstanceOf(TextContent.class);
    }

    @Test
    void textContent_deserializesAsAssistantContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"text\",\"text\":\"hello\"}";
        AssistantContentBlock block = MAPPER.readValue(json, AssistantContentBlock.class);
        assertThat(block).isInstanceOf(TextContent.class);
    }

    // --- ThinkingContent ---

    @Test
    void thinkingContent_serializesWithTypeField() throws JsonProcessingException {
        var tc = new ThinkingContent("thinking", "let me think...", null, null);
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"type\":\"thinking\"");
        assertThat(json).contains("\"thinking\":\"let me think...\"");
        assertThat(json).doesNotContain("thinkingSignature");
        assertThat(json).doesNotContain("redacted");
    }

    @Test
    void thinkingContent_withAllFields_serializes() throws JsonProcessingException {
        var tc = new ThinkingContent("thinking", "redacted content", "sig-xyz", true);
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"thinkingSignature\":\"sig-xyz\"");
        assertThat(json).contains("\"redacted\":true");
    }

    @Test
    void thinkingContent_roundTrip() throws JsonProcessingException {
        var original = new ThinkingContent("thinking", "deep thought", "sig-1", false);
        String json = MAPPER.writeValueAsString(original);
        ThinkingContent deserialized = MAPPER.readValue(json, ThinkingContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void thinkingContent_convenienceConstructor() {
        var tc = new ThinkingContent("reasoning...");
        assertThat(tc.type()).isEqualTo("thinking");
        assertThat(tc.thinking()).isEqualTo("reasoning...");
        assertThat(tc.thinkingSignature()).isNull();
        assertThat(tc.redacted()).isNull();
    }

    @Test
    void thinkingContent_deserializesAsContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"thinking\",\"thinking\":\"hmm\"}";
        ContentBlock block = MAPPER.readValue(json, ContentBlock.class);
        assertThat(block).isInstanceOf(ThinkingContent.class);
    }

    // --- ImageContent ---

    @Test
    void imageContent_serializesWithTypeField() throws JsonProcessingException {
        var ic = new ImageContent("image", "aGVsbG8=", "image/png");
        String json = MAPPER.writeValueAsString(ic);
        assertThat(json).contains("\"type\":\"image\"");
        assertThat(json).contains("\"data\":\"aGVsbG8=\"");
        assertThat(json).contains("\"mimeType\":\"image/png\"");
    }

    @Test
    void imageContent_roundTrip() throws JsonProcessingException {
        var original = new ImageContent("image", "base64data==", "image/jpeg");
        String json = MAPPER.writeValueAsString(original);
        ImageContent deserialized = MAPPER.readValue(json, ImageContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void imageContent_convenienceConstructor() {
        var ic = new ImageContent("data123", "image/gif");
        assertThat(ic.type()).isEqualTo("image");
        assertThat(ic.data()).isEqualTo("data123");
        assertThat(ic.mimeType()).isEqualTo("image/gif");
    }

    @Test
    void imageContent_deserializesAsContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"image\",\"data\":\"abc\",\"mimeType\":\"image/png\"}";
        ContentBlock block = MAPPER.readValue(json, ContentBlock.class);
        assertThat(block).isInstanceOf(ImageContent.class);
    }

    @Test
    void imageContent_deserializesAsUserContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"image\",\"data\":\"abc\",\"mimeType\":\"image/png\"}";
        UserContentBlock block = MAPPER.readValue(json, UserContentBlock.class);
        assertThat(block).isInstanceOf(ImageContent.class);
    }

    // --- ToolCall ---

    @Test
    void toolCall_serializesWithTypeField() throws JsonProcessingException {
        var tc = new ToolCall("toolCall", "call-1", "search", Map.of("query", "hello"), null);
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"type\":\"toolCall\"");
        assertThat(json).contains("\"id\":\"call-1\"");
        assertThat(json).contains("\"name\":\"search\"");
        assertThat(json).contains("\"query\":\"hello\"");
        assertThat(json).doesNotContain("thoughtSignature");
    }

    @Test
    void toolCall_withThoughtSignature_serializes() throws JsonProcessingException {
        var tc = new ToolCall("toolCall", "call-2", "calc", Map.of("x", 42), "thought-sig");
        String json = MAPPER.writeValueAsString(tc);
        assertThat(json).contains("\"thoughtSignature\":\"thought-sig\"");
    }

    @Test
    void toolCall_roundTrip() throws JsonProcessingException {
        var original = new ToolCall("toolCall", "id-1", "tool", Map.of("a", "b"), "sig");
        String json = MAPPER.writeValueAsString(original);
        ToolCall deserialized = MAPPER.readValue(json, ToolCall.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void toolCall_convenienceConstructor() {
        var tc = new ToolCall("id-1", "myTool", Map.of("key", "val"));
        assertThat(tc.type()).isEqualTo("toolCall");
        assertThat(tc.id()).isEqualTo("id-1");
        assertThat(tc.name()).isEqualTo("myTool");
        assertThat(tc.arguments()).containsEntry("key", "val");
        assertThat(tc.thoughtSignature()).isNull();
    }

    @Test
    void toolCall_deserializesAsContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"toolCall\",\"id\":\"1\",\"name\":\"t\",\"arguments\":{}}";
        ContentBlock block = MAPPER.readValue(json, ContentBlock.class);
        assertThat(block).isInstanceOf(ToolCall.class);
    }

    @Test
    void toolCall_deserializesAsAssistantContentBlock() throws JsonProcessingException {
        String json = "{\"type\":\"toolCall\",\"id\":\"1\",\"name\":\"t\",\"arguments\":{}}";
        AssistantContentBlock block = MAPPER.readValue(json, AssistantContentBlock.class);
        assertThat(block).isInstanceOf(ToolCall.class);
    }

    // --- Unknown fields ---

    @Test
    void contentBlock_ignoresUnknownFields() throws JsonProcessingException {
        String json = "{\"type\":\"text\",\"text\":\"hi\",\"unknownField\":123}";
        ContentBlock block = MAPPER.readValue(json, ContentBlock.class);
        assertThat(block).isInstanceOf(TextContent.class);
        assertThat(((TextContent) block).text()).isEqualTo("hi");
    }

    // --- Polymorphic via sealed interface hierarchy ---

    @Test
    void userContentBlock_polymorphicDeserialization() throws JsonProcessingException {
        String textJson = "{\"type\":\"text\",\"text\":\"hello\"}";
        String imageJson = "{\"type\":\"image\",\"data\":\"abc\",\"mimeType\":\"image/png\"}";

        UserContentBlock text = MAPPER.readValue(textJson, UserContentBlock.class);
        UserContentBlock image = MAPPER.readValue(imageJson, UserContentBlock.class);

        assertThat(text).isInstanceOf(TextContent.class);
        assertThat(image).isInstanceOf(ImageContent.class);
    }

    @Test
    void assistantContentBlock_polymorphicDeserialization() throws JsonProcessingException {
        String textJson = "{\"type\":\"text\",\"text\":\"hello\"}";
        String thinkingJson = "{\"type\":\"thinking\",\"thinking\":\"hmm\"}";
        String toolCallJson = "{\"type\":\"toolCall\",\"id\":\"1\",\"name\":\"t\",\"arguments\":{}}";

        AssistantContentBlock text = MAPPER.readValue(textJson, AssistantContentBlock.class);
        AssistantContentBlock thinking = MAPPER.readValue(thinkingJson, AssistantContentBlock.class);
        AssistantContentBlock toolCall = MAPPER.readValue(toolCallJson, AssistantContentBlock.class);

        assertThat(text).isInstanceOf(TextContent.class);
        assertThat(thinking).isInstanceOf(ThinkingContent.class);
        assertThat(toolCall).isInstanceOf(ToolCall.class);
    }
}
