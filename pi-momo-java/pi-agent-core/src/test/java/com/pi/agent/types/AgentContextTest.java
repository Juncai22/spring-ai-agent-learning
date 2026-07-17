package com.pi.agent.types;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentContext}.
 *
 * <p><b>Validates: Requirements 9.1, 9.2</b>
 */
class AgentContextTest {

    // ---- simple stub for testing ----
    private static AgentMessage stubMessage(String role) {
        return new AgentMessage() {
            @Override public String role() { return role; }
            @Override public long timestamp() { return System.currentTimeMillis(); }
        };
    }

    // ==================== Default constructor ====================

    @Test
    void defaultConstructor_setsEmptyDefaults() {
        AgentContext ctx = new AgentContext();

        assertThat(ctx.getSystemPrompt()).isEmpty();
        assertThat(ctx.getMessages()).isNotNull().isEmpty();
        assertThat(ctx.getTools()).isNull();
    }

    // ==================== Full constructor ====================

    @Test
    void fullConstructor_setsAllFields() {
        List<AgentMessage> msgs = new ArrayList<>(List.of(stubMessage("user")));
        List<AgentTool> tools = List.of();

        AgentContext ctx = new AgentContext("prompt", msgs, tools);

        assertThat(ctx.getSystemPrompt()).isEqualTo("prompt");
        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getTools()).isSameAs(tools);
    }

    @Test
    void fullConstructor_nullSystemPrompt_defaultsToEmpty() {
        AgentContext ctx = new AgentContext(null, null, null);

        assertThat(ctx.getSystemPrompt()).isEmpty();
        assertThat(ctx.getMessages()).isNotNull().isEmpty();
        assertThat(ctx.getTools()).isNull();
    }

    // ==================== Builder ====================

    @Test
    void builder_defaultValues() {
        AgentContext ctx = AgentContext.builder().build();

        assertThat(ctx.getSystemPrompt()).isEmpty();
        assertThat(ctx.getMessages()).isNotNull().isEmpty();
        assertThat(ctx.getTools()).isNull();
    }

    @Test
    void builder_setsAllFields() {
        AgentMessage msg = stubMessage("user");
        List<AgentTool> tools = List.of();

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("sys")
                .messages(List.of(msg))
                .tools(tools)
                .build();

        assertThat(ctx.getSystemPrompt()).isEqualTo("sys");
        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getTools()).isSameAs(tools);
    }

    @Test
    void builder_nullSystemPrompt_defaultsToEmpty() {
        AgentContext ctx = AgentContext.builder()
                .systemPrompt(null)
                .build();

        assertThat(ctx.getSystemPrompt()).isEmpty();
    }

    // ==================== Requirement 9.2: messages list is mutable ====================

    @Test
    void messagesListIsMutable_canAppendDirectly() {
        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .build();

        AgentMessage msg = stubMessage("user");
        ctx.getMessages().add(msg);

        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getMessages().get(0).role()).isEqualTo("user");
    }

    @Test
    void messagesListIsMutable_builderCopiesInput() {
        List<AgentMessage> original = new ArrayList<>(List.of(stubMessage("user")));

        AgentContext ctx = AgentContext.builder()
                .messages(original)
                .build();

        // Mutating the builder input should not affect the context
        original.add(stubMessage("assistant"));
        assertThat(ctx.getMessages()).hasSize(1);

        // Mutating the context messages should work
        ctx.getMessages().add(stubMessage("assistant"));
        assertThat(ctx.getMessages()).hasSize(2);
    }

    @Test
    void messagesListIsMutable_fullConstructorCopiesInput() {
        List<AgentMessage> original = new ArrayList<>(List.of(stubMessage("user")));

        AgentContext ctx = new AgentContext("prompt", original, null);

        // Mutating the original should not affect the context
        original.add(stubMessage("assistant"));
        assertThat(ctx.getMessages()).hasSize(1);
    }

    // ==================== Setters ====================

    @Test
    void setSystemPrompt_nullDefaultsToEmpty() {
        AgentContext ctx = new AgentContext();
        ctx.setSystemPrompt(null);
        assertThat(ctx.getSystemPrompt()).isEmpty();
    }

    @Test
    void setMessages_nullDefaultsToEmptyMutableList() {
        AgentContext ctx = new AgentContext();
        ctx.setMessages(null);
        assertThat(ctx.getMessages()).isNotNull().isEmpty();
        // Should still be mutable
        ctx.getMessages().add(stubMessage("user"));
        assertThat(ctx.getMessages()).hasSize(1);
    }

    @Test
    void setMessages_copiesInput() {
        AgentContext ctx = new AgentContext();
        List<AgentMessage> original = new ArrayList<>(List.of(stubMessage("user")));
        ctx.setMessages(original);

        original.add(stubMessage("assistant"));
        assertThat(ctx.getMessages()).hasSize(1);
    }

    @Test
    void setTools_acceptsNull() {
        AgentContext ctx = new AgentContext();
        ctx.setTools(null);
        assertThat(ctx.getTools()).isNull();
    }

    @Test
    void setTools_acceptsList() {
        AgentContext ctx = new AgentContext();
        List<AgentTool> tools = List.of();
        ctx.setTools(tools);
        assertThat(ctx.getTools()).isSameAs(tools);
    }
}
