package com.pi.coding.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: Session Entry Round-Trip
 *
 * <p>For all SessionEntry subtypes, serialize to JSON and deserialize back,
 * verifying the result is equivalent to the original.
 *
 * <p><b>Validates: Requirement 1.18</b>
 */
class SessionEntrySerializationPropertyTest {

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
    Arbitrary<String> entryIds() {
        return Arbitraries.strings()
                .ofLength(8)
                .alpha()
                .numeric();
    }

    @Provide
    Arbitrary<String> timestamps() {
        return Arbitraries.of(
                "2024-01-15T10:30:00.000Z",
                "2024-06-20T15:45:30.123Z",
                "2024-12-31T23:59:59.999Z"
        );
    }

    @Provide
    Arbitrary<Long> timestampMillis() {
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
        return Combinators.combine(safeStrings(), timestampMillis())
                .as((text, ts) -> new UserMessage("user", text, ts));
    }

    @Provide
    Arbitrary<AssistantMessage> assistantMessages() {
        return Combinators.combine(
                assistantContentBlocks().list().ofMinSize(1).ofMaxSize(3),
                safeStrings(),
                safeStrings(),
                safeStrings(),
                timestampMillis(),
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
                timestampMillis()
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

    // ==================== SessionEntry generators ====================

    @Provide
    Arbitrary<SessionMessageEntry> sessionMessageEntries() {
        return Combinators.combine(entryIds(), entryIds().injectNull(0.2), timestamps(), agentMessages())
                .as(SessionMessageEntry::create);
    }

    @Provide
    Arbitrary<ThinkingLevelChangeEntry> thinkingLevelChangeEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                Arbitraries.of("off", "low", "medium", "high")
        ).as(ThinkingLevelChangeEntry::create);
    }

    @Provide
    Arbitrary<ModelChangeEntry> modelChangeEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                Arbitraries.of("anthropic", "openai", "bedrock"),
                safeStrings()
        ).as(ModelChangeEntry::create);
    }

    @Provide
    Arbitrary<CompactionEntry<String>> compactionEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                safeStrings(),
                entryIds(),
                Arbitraries.integers().between(1000, 100000),
                safeStrings().injectNull(0.5),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.5)
        ).as((id, parentId, ts, summary, firstKeptId, tokens, details, fromHook) ->
                CompactionEntry.create(id, parentId, ts, summary, firstKeptId, tokens, details, fromHook));
    }

    @Provide
    Arbitrary<BranchSummaryEntry<String>> branchSummaryEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                entryIds(),
                safeStrings(),
                safeStrings().injectNull(0.5),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.5)
        ).as(BranchSummaryEntry::create);
    }

    @Provide
    Arbitrary<CustomEntry<String>> customEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                safeStrings(),
                safeStrings().injectNull(0.5)
        ).as(CustomEntry::create);
    }

    @Provide
    Arbitrary<CustomMessageEntry<String>> customMessageEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                safeStrings(),
                safeStrings(),
                Arbitraries.of(true, false),
                safeStrings().injectNull(0.5)
        ).as(CustomMessageEntry::create);
    }

    @Provide
    Arbitrary<LabelEntry> labelEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                entryIds(),
                safeStrings().injectNull(0.3)
        ).as(LabelEntry::create);
    }

    @Provide
    Arbitrary<SessionInfoEntry> sessionInfoEntries() {
        return Combinators.combine(
                entryIds(),
                entryIds().injectNull(0.2),
                timestamps(),
                safeStrings().injectNull(0.3)
        ).as(SessionInfoEntry::create);
    }

    @Provide
    Arbitrary<SessionEntry> allSessionEntries() {
        return Arbitraries.oneOf(
                sessionMessageEntries().map(e -> e),
                thinkingLevelChangeEntries().map(e -> e),
                modelChangeEntries().map(e -> e),
                compactionEntries().map(e -> e),
                branchSummaryEntries().map(e -> e),
                customEntries().map(e -> e),
                customMessageEntries().map(e -> e),
                labelEntries().map(e -> e),
                sessionInfoEntries().map(e -> e)
        );
    }

    // ==================== Property tests ====================

    /**
     * Property 1: Session Entry Round-Trip
     *
     * <p>For all SessionEntry subtypes, serialize to JSON and deserialize back,
     * verifying the result is equivalent to the original.
     *
     * <p><b>Validates: Requirement 1.18</b>
     */
    @Property(tries = 200)
    void sessionEntry_roundTrip(
            @ForAll("allSessionEntries") SessionEntry original
    ) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        SessionEntry deserialized = MAPPER.readValue(json, SessionEntry.class);

        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.parentId()).isEqualTo(original.parentId());
        assertThat(deserialized.timestamp()).isEqualTo(original.timestamp());
        assertThat(deserialized.getClass()).isEqualTo(original.getClass());

        // Type-specific assertions
        if (original instanceof SessionMessageEntry orig) {
            SessionMessageEntry deser = (SessionMessageEntry) deserialized;
            assertAgentMessagesEqual(orig.message(), deser.message());

        } else if (original instanceof ThinkingLevelChangeEntry orig) {
            ThinkingLevelChangeEntry deser = (ThinkingLevelChangeEntry) deserialized;
            assertThat(deser.thinkingLevel()).isEqualTo(orig.thinkingLevel());

        } else if (original instanceof ModelChangeEntry orig) {
            ModelChangeEntry deser = (ModelChangeEntry) deserialized;
            assertThat(deser.provider()).isEqualTo(orig.provider());
            assertThat(deser.modelId()).isEqualTo(orig.modelId());

        } else if (original instanceof CompactionEntry<?> orig) {
            CompactionEntry<?> deser = (CompactionEntry<?>) deserialized;
            assertThat(deser.summary()).isEqualTo(orig.summary());
            assertThat(deser.firstKeptEntryId()).isEqualTo(orig.firstKeptEntryId());
            assertThat(deser.tokensBefore()).isEqualTo(orig.tokensBefore());
            assertThat(deser.details()).isEqualTo(orig.details());
            assertThat(deser.fromHook()).isEqualTo(orig.fromHook());

        } else if (original instanceof BranchSummaryEntry<?> orig) {
            BranchSummaryEntry<?> deser = (BranchSummaryEntry<?>) deserialized;
            assertThat(deser.fromId()).isEqualTo(orig.fromId());
            assertThat(deser.summary()).isEqualTo(orig.summary());
            assertThat(deser.details()).isEqualTo(orig.details());
            assertThat(deser.fromHook()).isEqualTo(orig.fromHook());

        } else if (original instanceof CustomEntry<?> orig) {
            CustomEntry<?> deser = (CustomEntry<?>) deserialized;
            assertThat(deser.customType()).isEqualTo(orig.customType());
            assertThat(deser.data()).isEqualTo(orig.data());

        } else if (original instanceof CustomMessageEntry<?> orig) {
            CustomMessageEntry<?> deser = (CustomMessageEntry<?>) deserialized;
            assertThat(deser.customType()).isEqualTo(orig.customType());
            assertThat(deser.content()).isEqualTo(orig.content());
            assertThat(deser.display()).isEqualTo(orig.display());
            assertThat(deser.details()).isEqualTo(orig.details());

        } else if (original instanceof LabelEntry orig) {
            LabelEntry deser = (LabelEntry) deserialized;
            assertThat(deser.targetId()).isEqualTo(orig.targetId());
            assertThat(deser.label()).isEqualTo(orig.label());

        } else if (original instanceof SessionInfoEntry orig) {
            SessionInfoEntry deser = (SessionInfoEntry) deserialized;
            assertThat(deser.name()).isEqualTo(orig.name());
        }
    }

    /**
     * Property: SessionHeader round-trip serialization.
     */
    @Property(tries = 100)
    void sessionHeader_roundTrip(
            @ForAll("entryIds") String id,
            @ForAll("timestamps") String timestamp,
            @ForAll("safeStrings") String cwd,
            @ForAll("safeStrings") @net.jqwik.api.constraints.WithNull(0.5) String parentSession
    ) throws JsonProcessingException {
        SessionHeader original = SessionHeader.create(id, timestamp, cwd, parentSession);

        String json = MAPPER.writeValueAsString(original);
        SessionHeader deserialized = MAPPER.readValue(json, SessionHeader.class);

        assertThat(deserialized.type()).isEqualTo("session");
        assertThat(deserialized.version()).isEqualTo(SessionHeader.CURRENT_VERSION);
        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.timestamp()).isEqualTo(original.timestamp());
        assertThat(deserialized.cwd()).isEqualTo(original.cwd());
        assertThat(deserialized.parentSession()).isEqualTo(original.parentSession());
    }

    // ==================== Assertion helpers ====================

    private void assertAgentMessagesEqual(AgentMessage expected, AgentMessage actual) {
        assertThat(actual).isInstanceOf(MessageAdapter.class);
        assertThat(expected).isInstanceOf(MessageAdapter.class);

        Message expectedMsg = ((MessageAdapter) expected).message();
        Message actualMsg = ((MessageAdapter) actual).message();

        assertThat(actualMsg.role()).isEqualTo(expectedMsg.role());
        assertThat(actualMsg.timestamp()).isEqualTo(expectedMsg.timestamp());

        if (expectedMsg instanceof UserMessage um) {
            assertThat(actualMsg).isInstanceOf(UserMessage.class);
            UserMessage aum = (UserMessage) actualMsg;
            assertThat(aum.content()).isEqualTo(um.content());

        } else if (expectedMsg instanceof AssistantMessage am) {
            assertThat(actualMsg).isInstanceOf(AssistantMessage.class);
            AssistantMessage aam = (AssistantMessage) actualMsg;
            assertThat(aam.getContent()).isEqualTo(am.getContent());
            assertThat(aam.getApi()).isEqualTo(am.getApi());
            assertThat(aam.getProvider()).isEqualTo(am.getProvider());
            assertThat(aam.getModel()).isEqualTo(am.getModel());
            assertThat(aam.getStopReason()).isEqualTo(am.getStopReason());
            assertThat(aam.getUsage()).isEqualTo(am.getUsage());

        } else if (expectedMsg instanceof ToolResultMessage trm) {
            assertThat(actualMsg).isInstanceOf(ToolResultMessage.class);
            ToolResultMessage atrm = (ToolResultMessage) actualMsg;
            assertThat(atrm.toolCallId()).isEqualTo(trm.toolCallId());
            assertThat(atrm.toolName()).isEqualTo(trm.toolName());
            assertThat(atrm.isError()).isEqualTo(trm.isError());
            assertThat(atrm.content()).isEqualTo(trm.content());
        }
    }
}
