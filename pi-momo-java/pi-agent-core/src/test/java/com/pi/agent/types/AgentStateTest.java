package com.pi.agent.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentState}.
 *
 * <p><b>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 39.4</b>
 */
class AgentStateTest {

    private static AgentMessage stubMessage(String role) {
        return new AgentMessage() {
            @Override public String role() { return role; }
            @Override public long timestamp() { return 1000L; }
        };
    }

    // ==================== Default constructor (Req 10.1) ====================

    @Test
    void defaultConstructor_setsAllDefaults() {
        AgentState state = new AgentState();

        assertThat(state.getSystemPrompt()).isEmpty();
        assertThat(state.getModel()).isNull();
        assertThat(state.getThinkingLevel()).isEqualTo(AgentThinkingLevel.OFF);
        assertThat(state.getTools()).isNotNull().isEmpty();
        assertThat(state.getMessages()).isNotNull().isEmpty();
        assertThat(state.isStreaming()).isFalse();
        assertThat(state.getStreamMessage()).isNull();
        assertThat(state.getPendingToolCalls()).isNotNull().isEmpty();
        assertThat(state.getError()).isNull();
    }

    // ==================== Setters ====================

    @Test
    void setSystemPrompt_nullDefaultsToEmpty() {
        AgentState state = new AgentState();
        state.setSystemPrompt(null);
        assertThat(state.getSystemPrompt()).isEmpty();
    }

    @Test
    void setSystemPrompt_setsValue() {
        AgentState state = new AgentState();
        state.setSystemPrompt("You are helpful.");
        assertThat(state.getSystemPrompt()).isEqualTo("You are helpful.");
    }

    @Test
    void setThinkingLevel_nullDefaultsToOff() {
        AgentState state = new AgentState();
        state.setThinkingLevel(AgentThinkingLevel.HIGH);
        state.setThinkingLevel(null);
        assertThat(state.getThinkingLevel()).isEqualTo(AgentThinkingLevel.OFF);
    }

    @Test
    void setTools_nullDefaultsToEmptyList() {
        AgentState state = new AgentState();
        state.setTools(null);
        assertThat(state.getTools()).isNotNull().isEmpty();
    }

    @Test
    void setMessages_nullDefaultsToEmptyMutableList() {
        AgentState state = new AgentState();
        state.setMessages(null);
        assertThat(state.getMessages()).isNotNull().isEmpty();
        // Should still be mutable
        state.getMessages().add(stubMessage("user"));
        assertThat(state.getMessages()).hasSize(1);
    }

    @Test
    void setMessages_copiesInput() {
        AgentState state = new AgentState();
        List<AgentMessage> original = new ArrayList<>(List.of(stubMessage("user")));
        state.setMessages(original);

        original.add(stubMessage("assistant"));
        assertThat(state.getMessages()).hasSize(1);
    }

    // ==================== isStreaming (Req 10.2, 10.3, 39.4) ====================

    @Test
    void setIsStreaming_togglesValue() {
        AgentState state = new AgentState();
        assertThat(state.isStreaming()).isFalse();

        state.setIsStreaming(true);
        assertThat(state.isStreaming()).isTrue();

        state.setIsStreaming(false);
        assertThat(state.isStreaming()).isFalse();
    }

    // ==================== streamMessage ====================

    @Test
    void setStreamMessage_setsAndClears() {
        AgentState state = new AgentState();
        AgentMessage msg = stubMessage("assistant");

        state.setStreamMessage(msg);
        assertThat(state.getStreamMessage()).isSameAs(msg);

        state.setStreamMessage(null);
        assertThat(state.getStreamMessage()).isNull();
    }

    // ==================== pendingToolCalls (Req 10.4, 10.5) ====================

    @Test
    void addPendingToolCall_addsToSet() {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");
        state.addPendingToolCall("tc-2");

        assertThat(state.getPendingToolCalls()).containsExactlyInAnyOrder("tc-1", "tc-2");
    }

    @Test
    void removePendingToolCall_removesFromSet() {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");
        state.addPendingToolCall("tc-2");
        state.removePendingToolCall("tc-1");

        assertThat(state.getPendingToolCalls()).containsExactly("tc-2");
    }

    @Test
    void clearPendingToolCalls_emptiesSet() {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");
        state.clearPendingToolCalls();

        assertThat(state.getPendingToolCalls()).isEmpty();
    }

    @Test
    void setPendingToolCalls_replacesWithCopyOnWriteArraySet() {
        AgentState state = new AgentState();
        state.setPendingToolCalls(Set.of("a", "b"));

        assertThat(state.getPendingToolCalls()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void setPendingToolCalls_nullDefaultsToEmpty() {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");
        state.setPendingToolCalls(null);

        assertThat(state.getPendingToolCalls()).isEmpty();
    }

    @Test
    void getPendingToolCalls_returnsUnmodifiableView() {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");

        Set<String> view = state.getPendingToolCalls();
        assertThatThrownBy(() -> view.add("tc-2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== error ====================

    @Test
    void setError_setsAndClears() {
        AgentState state = new AgentState();
        state.setError("something went wrong");
        assertThat(state.getError()).isEqualTo("something went wrong");

        state.setError(null);
        assertThat(state.getError()).isNull();
    }

    // ==================== Jackson serialization (Req 40.3) ====================

    @Test
    void jacksonSerialization_defaultState_producesValidJson() throws Exception {
        AgentState state = new AgentState();

        String json = PiAiJson.MAPPER.writeValueAsString(state);
        JsonNode node = PiAiJson.MAPPER.readTree(json);

        assertThat(node.has("systemPrompt")).isTrue();
        assertThat(node.get("systemPrompt").asText()).isEmpty();
        assertThat(node.has("isStreaming")).isTrue();
        assertThat(node.get("isStreaming").asBoolean()).isFalse();
        assertThat(node.has("thinkingLevel")).isTrue();
        assertThat(node.get("thinkingLevel").asText()).isEqualTo("OFF");
        assertThat(node.has("messages")).isTrue();
        assertThat(node.get("messages").isArray()).isTrue();
        assertThat(node.has("pendingToolCalls")).isTrue();
        assertThat(node.get("pendingToolCalls").isArray()).isTrue();
        // tools should be excluded (@JsonIgnore)
        assertThat(node.has("tools")).isFalse();
        // null fields excluded by @JsonInclude(NON_NULL)
        assertThat(node.has("model")).isFalse();
        assertThat(node.has("streamMessage")).isFalse();
        assertThat(node.has("error")).isFalse();
    }

    @Test
    void jacksonSerialization_withError_includesErrorField() throws Exception {
        AgentState state = new AgentState();
        state.setError("timeout");

        String json = PiAiJson.MAPPER.writeValueAsString(state);
        JsonNode node = PiAiJson.MAPPER.readTree(json);

        assertThat(node.has("error")).isTrue();
        assertThat(node.get("error").asText()).isEqualTo("timeout");
    }

    @Test
    void jacksonSerialization_withPendingToolCalls_serializesAsArray() throws Exception {
        AgentState state = new AgentState();
        state.addPendingToolCall("tc-1");
        state.addPendingToolCall("tc-2");

        String json = PiAiJson.MAPPER.writeValueAsString(state);
        JsonNode node = PiAiJson.MAPPER.readTree(json);

        assertThat(node.get("pendingToolCalls").isArray()).isTrue();
        assertThat(node.get("pendingToolCalls")).hasSize(2);
    }
}
