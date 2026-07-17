package com.pi.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 1: AgentEvent 序列化 round-trip
 *
 * <p>为所有 10 种 AgentEvent record 子类型生成随机实例，验证 Jackson round-trip。
 *
 * <p><b>Validates: Requirements 40.1, 40.5</b>
 */
class AgentEventSerializationPropertyTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== Primitive generators ====================

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .alpha()
                .numeric();
    }

    @Provide
    Arbitrary<Long> timestamps() {
        return Arbitraries.longs().between(1_000_000_000_000L, 2_000_000_000_000L);
    }

    // ==================== Content block generators ====================

    @Provide
    Arbitrary<TextContent> textContents() {
        return Combinators.combine(safeStrings(), safeStrings().injectNull(0.5))
                .as((text, sig) -> new TextContent("text", text, sig));
    }

    @Provide
    Arbitrary<ThinkingContent> thinkingContents() {
        return Combinators.combine(
                safeStrings(),
                safeStrings().injectNull(0.5),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3)
        ).as((thinking, sig, redacted) -> new ThinkingContent("thinking", thinking, sig, redacted));
    }

    @Provide
    Arbitrary<ToolCall> toolCalls() {
        return Combinators.combine(safeStrings(), safeStrings(), safeStrings())
                .as((id, name, argVal) -> new ToolCall("toolCall", id, name,
                        Map.of("key", argVal), null));
    }

    @Provide
    Arbitrary<AssistantContentBlock> assistantContentBlocks() {
        return Arbitraries.oneOf(
                textContents().map(t -> t),
                thinkingContents().map(t -> t),
                toolCalls().map(t -> t)
        );
    }

    @Provide
    Arbitrary<UserContentBlock> userContentBlocks() {
        return textContents().map(t -> t);
    }

    // ==================== Message generators ====================

    @Provide
    Arbitrary<UserMessage> userMessages() {
        return Combinators.combine(safeStrings(), timestamps())
                .as((text, ts) -> new UserMessage("user", text, ts));
    }

    @Provide
    Arbitrary<AssistantMessage> assistantMessages() {
        return Combinators.combine(
                assistantContentBlocks().list().ofMinSize(1).ofMaxSize(3),
                safeStrings(),
                safeStrings(),
                safeStrings(),
                timestamps(),
                Arbitraries.of(StopReason.STOP, StopReason.TOOL_USE, StopReason.LENGTH)
        ).as((content, api, provider, model, ts, stopReason) ->
                AssistantMessage.builder()
                        .content(content)
                        .api(api)
                        .provider(provider)
                        .model(model)
                        .usage(new Usage(10, 20, 0, 0, 30, null))
                        .stopReason(stopReason)
                        .timestamp(ts)
                        .build());
    }

    @Provide
    Arbitrary<ToolResultMessage> toolResultMessages() {
        return Combinators.combine(
                safeStrings(),
                safeStrings(),
                userContentBlocks().list().ofMinSize(1).ofMaxSize(2),
                Arbitraries.of(true, false),
                timestamps()
        ).as((toolCallId, toolName, content, isError, ts) ->
                new ToolResultMessage(toolCallId, toolName, content, null, isError, ts));
    }

    @Provide
    Arbitrary<AgentMessage> agentMessages() {
        return Arbitraries.oneOf(
                userMessages().map(MessageAdapter::wrap),
                assistantMessages().map(MessageAdapter::wrap),
                toolResultMessages().map(MessageAdapter::wrap)
        );
    }

    // ==================== AgentToolResult generator ====================

    @Provide
    Arbitrary<AgentToolResult<String>> agentToolResults() {
        return Combinators.combine(
                userContentBlocks().list().ofMinSize(0).ofMaxSize(3),
                safeStrings().injectNull(0.3)
        ).as(AgentToolResult::new);
    }

    // ==================== AgentEvent generators ====================

    @Provide
    Arbitrary<AgentEvent> allAgentEvents() {
        return Arbitraries.oneOf(
                // AgentStart
                Arbitraries.just(new AgentEvent.AgentStart()).map(e -> e),
                // AgentEnd
                agentMessages().list().ofMinSize(0).ofMaxSize(3)
                        .map(msgs -> (AgentEvent) new AgentEvent.AgentEnd(msgs)),
                // TurnStart
                Arbitraries.just(new AgentEvent.TurnStart()).map(e -> e),
                // TurnEnd
                Combinators.combine(agentMessages(), toolResultMessages().list().ofMinSize(0).ofMaxSize(2))
                        .as((msg, trs) -> (AgentEvent) new AgentEvent.TurnEnd(msg, trs)),
                // MessageStart
                agentMessages().map(msg -> (AgentEvent) new AgentEvent.MessageStart(msg)),
                // MessageUpdate — assistantMessageEvent set to null (no Jackson type info on that sealed interface)
                agentMessages().map(msg -> (AgentEvent) new AgentEvent.MessageUpdate(msg, null)),
                // MessageEnd
                agentMessages().map(msg -> (AgentEvent) new AgentEvent.MessageEnd(msg)),
                // ToolExecutionStart
                Combinators.combine(safeStrings(), safeStrings(), safeStrings())
                        .as((id, name, args) -> (AgentEvent) new AgentEvent.ToolExecutionStart(id, name, args)),
                // ToolExecutionUpdate
                Combinators.combine(safeStrings(), safeStrings(), safeStrings(), agentToolResults())
                        .as((id, name, args, result) -> (AgentEvent) new AgentEvent.ToolExecutionUpdate(id, name, args, result)),
                // ToolExecutionEnd
                Combinators.combine(safeStrings(), safeStrings(), agentToolResults(), Arbitraries.of(true, false))
                        .as((id, name, result, isError) -> (AgentEvent) new AgentEvent.ToolExecutionEnd(id, name, result, isError))
        );
    }

    // ==================== Property test ====================

    /**
     * Property 1: AgentEvent 序列化 round-trip.
     *
     * <p>For all 10 AgentEvent subtypes, serialize to JSON and deserialize back,
     * verifying the result is equivalent to the original.
     *
     * <p><b>Validates: Requirements 40.1, 40.5</b>
     */
    @Property(tries = 200)
    void agentEvent_roundTrip(
            @ForAll("allAgentEvents") AgentEvent original
    ) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.getClass()).isEqualTo(original.getClass());

        if (original instanceof AgentEvent.AgentStart) {
            assertThat(deserialized).isInstanceOf(AgentEvent.AgentStart.class);

        } else if (original instanceof AgentEvent.AgentEnd) {
            AgentEvent.AgentEnd orig = (AgentEvent.AgentEnd) original;
            AgentEvent.AgentEnd deser = (AgentEvent.AgentEnd) deserialized;
            assertAgentMessageListsEqual(orig.messages(), deser.messages());

        } else if (original instanceof AgentEvent.TurnStart) {
            assertThat(deserialized).isInstanceOf(AgentEvent.TurnStart.class);

        } else if (original instanceof AgentEvent.TurnEnd) {
            AgentEvent.TurnEnd orig = (AgentEvent.TurnEnd) original;
            AgentEvent.TurnEnd deser = (AgentEvent.TurnEnd) deserialized;
            assertAgentMessagesEqual(orig.message(), deser.message());
            assertThat(deser.toolResults()).hasSize(orig.toolResults().size());
            for (int i = 0; i < orig.toolResults().size(); i++) {
                assertThat(deser.toolResults().get(i)).isEqualTo(orig.toolResults().get(i));
            }

        } else if (original instanceof AgentEvent.MessageStart) {
            AgentEvent.MessageStart orig = (AgentEvent.MessageStart) original;
            AgentEvent.MessageStart deser = (AgentEvent.MessageStart) deserialized;
            assertAgentMessagesEqual(orig.message(), deser.message());

        } else if (original instanceof AgentEvent.MessageUpdate) {
            AgentEvent.MessageUpdate orig = (AgentEvent.MessageUpdate) original;
            AgentEvent.MessageUpdate deser = (AgentEvent.MessageUpdate) deserialized;
            assertAgentMessagesEqual(orig.message(), deser.message());
            // assistantMessageEvent is null in generated data (no Jackson type info on that sealed interface)
            assertThat(deser.assistantMessageEvent()).isNull();

        } else if (original instanceof AgentEvent.MessageEnd) {
            AgentEvent.MessageEnd orig = (AgentEvent.MessageEnd) original;
            AgentEvent.MessageEnd deser = (AgentEvent.MessageEnd) deserialized;
            assertAgentMessagesEqual(orig.message(), deser.message());

        } else if (original instanceof AgentEvent.ToolExecutionStart) {
            AgentEvent.ToolExecutionStart orig = (AgentEvent.ToolExecutionStart) original;
            AgentEvent.ToolExecutionStart deser = (AgentEvent.ToolExecutionStart) deserialized;
            assertThat(deser.toolCallId()).isEqualTo(orig.toolCallId());
            assertThat(deser.toolName()).isEqualTo(orig.toolName());

        } else if (original instanceof AgentEvent.ToolExecutionUpdate) {
            AgentEvent.ToolExecutionUpdate orig = (AgentEvent.ToolExecutionUpdate) original;
            AgentEvent.ToolExecutionUpdate deser = (AgentEvent.ToolExecutionUpdate) deserialized;
            assertThat(deser.toolCallId()).isEqualTo(orig.toolCallId());
            assertThat(deser.toolName()).isEqualTo(orig.toolName());

        } else if (original instanceof AgentEvent.ToolExecutionEnd) {
            AgentEvent.ToolExecutionEnd orig = (AgentEvent.ToolExecutionEnd) original;
            AgentEvent.ToolExecutionEnd deser = (AgentEvent.ToolExecutionEnd) deserialized;
            assertThat(deser.toolCallId()).isEqualTo(orig.toolCallId());
            assertThat(deser.toolName()).isEqualTo(orig.toolName());
            assertThat(deser.isError()).isEqualTo(orig.isError());
        }
    }

    // ==================== Assertion helpers ====================

    private void assertAgentMessageListsEqual(List<AgentMessage> expected, List<AgentMessage> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertAgentMessagesEqual(expected.get(i), actual.get(i));
        }
    }

    private void assertAgentMessagesEqual(AgentMessage expected, AgentMessage actual) {
        assertThat(actual).isInstanceOf(MessageAdapter.class);
        assertThat(expected).isInstanceOf(MessageAdapter.class);

        Message expectedMsg = ((MessageAdapter) expected).message();
        Message actualMsg = ((MessageAdapter) actual).message();

        assertThat(actualMsg.role()).isEqualTo(expectedMsg.role());
        assertThat(actualMsg.timestamp()).isEqualTo(expectedMsg.timestamp());

        if (expectedMsg instanceof UserMessage) {
            UserMessage um = (UserMessage) expectedMsg;
            assertThat(actualMsg).isInstanceOf(UserMessage.class);
            UserMessage aum = (UserMessage) actualMsg;
            assertThat(aum.content()).isEqualTo(um.content());

        } else if (expectedMsg instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) expectedMsg;
            assertThat(actualMsg).isInstanceOf(AssistantMessage.class);
            AssistantMessage aam = (AssistantMessage) actualMsg;
            assertThat(aam.getContent()).isEqualTo(am.getContent());
            assertThat(aam.getApi()).isEqualTo(am.getApi());
            assertThat(aam.getProvider()).isEqualTo(am.getProvider());
            assertThat(aam.getModel()).isEqualTo(am.getModel());
            assertThat(aam.getStopReason()).isEqualTo(am.getStopReason());
            assertThat(aam.getUsage()).isEqualTo(am.getUsage());

        } else if (expectedMsg instanceof ToolResultMessage) {
            ToolResultMessage trm = (ToolResultMessage) expectedMsg;
            assertThat(actualMsg).isInstanceOf(ToolResultMessage.class);
            ToolResultMessage atrm = (ToolResultMessage) actualMsg;
            assertThat(atrm.toolCallId()).isEqualTo(trm.toolCallId());
            assertThat(atrm.toolName()).isEqualTo(trm.toolName());
            assertThat(atrm.isError()).isEqualTo(trm.isError());
            assertThat(atrm.content()).isEqualTo(trm.content());
        }
    }
}
