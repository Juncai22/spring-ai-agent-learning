package com.pi.coding.message;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.assertj.core.api.Assertions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Message Conversion Idempotence.
 *
 * <p>Verifies that convertToLlm on already-converted messages produces the same result.
 * Also verifies correct conversion of all custom message types.
 *
 * <p><b>Validates: Requirements 23.1-23.7</b>
 */
class MessageConversionIdempotencePropertyTest {

    @Property(tries = 100)
    void convertedMessagesAreIdempotent(
            @ForAll("standardMessages") List<AgentMessage> messages
    ) {
        // First conversion
        List<Message> first = MessageConverter.convertToLlm(messages);

        // Wrap converted messages back as AgentMessages
        List<AgentMessage> rewrapped = first.stream()
                .map(MessageAdapter::wrap)
                .toList();

        // Second conversion
        List<Message> second = MessageConverter.convertToLlm(rewrapped);

        // Same size
        assertThat(second).hasSameSizeAs(first);

        // Each message should have the same role and timestamp
        for (int i = 0; i < first.size(); i++) {
            Message m1 = first.get(i);
            Message m2 = second.get(i);
            assertThat(m2.role()).isEqualTo(m1.role());
            assertThat(m2.timestamp()).isEqualTo(m1.timestamp());
        }
    }

    @Property(tries = 100)
    void bashExecutionConvertsToUserMessage(
            @ForAll @StringLength(min = 1, max = 50) String command,
            @ForAll @StringLength(max = 200) String output,
            @ForAll @IntRange(min = 0, max = 255) int exitCode,
            @ForAll boolean cancelled,
            @ForAll long timestamp
    ) {
        BashExecutionMessage bash = new BashExecutionMessage(
                command, output, exitCode, cancelled, false, null, timestamp, null
        );
        List<Message> result = MessageConverter.convertToLlm(List.of(bash));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo("user");
        assertThat(result.get(0).timestamp()).isEqualTo(timestamp);
    }

    @Property(tries = 50)
    void bashExecutionExcludedFromContextIsFiltered(
            @ForAll @StringLength(min = 1, max = 50) String command,
            @ForAll long timestamp
    ) {
        BashExecutionMessage bash = new BashExecutionMessage(
                command, "", 0, false, false, null, timestamp, true
        );
        List<Message> result = MessageConverter.convertToLlm(List.of(bash));

        assertThat(result).isEmpty();
    }

    @Property(tries = 100)
    void branchSummaryConvertsWithPrefix(
            @ForAll @StringLength(min = 1, max = 200) String summary,
            @ForAll @StringLength(min = 1, max = 36) String fromId,
            @ForAll long timestamp
    ) {
        BranchSummaryMessage msg = new BranchSummaryMessage(summary, fromId, timestamp);
        List<Message> result = MessageConverter.convertToLlm(List.of(msg));

        assertThat(result).hasSize(1);
        Message converted = result.get(0);
        assertThat(converted.role()).isEqualTo("user");
        assertThat(converted).isInstanceOf(UserMessage.class);

        UserMessage um = (UserMessage) converted;
        @SuppressWarnings("unchecked")
        List<TextContent> blocks = (List<TextContent>) um.content();
        String text = blocks.get(0).text();
        assertThat(text).startsWith(MessageConverter.BRANCH_SUMMARY_PREFIX);
        assertThat(text).endsWith(MessageConverter.BRANCH_SUMMARY_SUFFIX);
        assertThat(text).contains(summary);
    }

    @Property(tries = 100)
    void compactionSummaryConvertsWithPrefix(
            @ForAll @StringLength(min = 1, max = 200) String summary,
            @ForAll @IntRange(min = 0, max = 100000) int tokensBefore,
            @ForAll long timestamp
    ) {
        CompactionSummaryMessage msg = new CompactionSummaryMessage(summary, tokensBefore, timestamp);
        List<Message> result = MessageConverter.convertToLlm(List.of(msg));

        assertThat(result).hasSize(1);
        Message converted = result.get(0);
        assertThat(converted.role()).isEqualTo("user");

        UserMessage um = (UserMessage) converted;
        @SuppressWarnings("unchecked")
        List<TextContent> blocks = (List<TextContent>) um.content();
        String text = blocks.get(0).text();
        assertThat(text).startsWith(MessageConverter.COMPACTION_SUMMARY_PREFIX);
        assertThat(text).endsWith(MessageConverter.COMPACTION_SUMMARY_SUFFIX);
        assertThat(text).contains(summary);
    }

    @Property(tries = 100)
    void customMessageConvertsToUserMessage(
            @ForAll @StringLength(min = 1, max = 100) String content,
            @ForAll @StringLength(min = 1, max = 30) String customType,
            @ForAll boolean display,
            @ForAll long timestamp
    ) {
        CustomMessage msg = new CustomMessage(customType, content, display, null, timestamp);
        List<Message> result = MessageConverter.convertToLlm(List.of(msg));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo("user");
        assertThat(result.get(0).timestamp()).isEqualTo(timestamp);
    }

    @Property(tries = 100)
    void standardLlmMessagesPassThrough(
            @ForAll("standardMessages") List<AgentMessage> messages
    ) {
        List<Message> result = MessageConverter.convertToLlm(messages);

        // All standard messages should pass through
        assertThat(result).hasSameSizeAs(messages);
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage original = messages.get(i);
            Message converted = result.get(i);
            // MessageAdapter unwraps to the original Message
            if (original instanceof MessageAdapter adapter) {
                assertThat(converted).isSameAs(adapter.message());
            }
        }
    }

    @Property(tries = 50)
    void outputNeverExceedsInputSize(
            @ForAll("mixedMessages") List<AgentMessage> messages
    ) {
        List<Message> result = MessageConverter.convertToLlm(messages);
        // Output can be smaller (filtered) but never larger than input
        assertThat(result.size()).isLessThanOrEqualTo(messages.size());
    }

    @Property(tries = 50)
    void allOutputMessagesHaveValidRole(
            @ForAll("mixedMessages") List<AgentMessage> messages
    ) {
        List<Message> result = MessageConverter.convertToLlm(messages);
        for (Message m : result) {
            assertThat(m.role()).isIn("user", "assistant", "toolResult");
        }
    }

    // ---- Providers ----

    @Provide
    Arbitrary<List<AgentMessage>> standardMessages() {
        return userMessageArbitrary().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<AgentMessage>> mixedMessages() {
        Arbitrary<AgentMessage> any = Arbitraries.oneOf(
                userMessageArbitrary(),
                bashExecutionArbitrary(),
                customMessageArbitrary(),
                branchSummaryArbitrary(),
                compactionSummaryArbitrary()
        );
        return any.list().ofMinSize(1).ofMaxSize(8);
    }

    private Arbitrary<AgentMessage> userMessageArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(100),
                Arbitraries.longs()
        ).as((text, ts) -> MessageAdapter.wrap(new UserMessage(text, ts)));
    }

    private Arbitrary<AgentMessage> bashExecutionArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(50),
                Arbitraries.strings().ofMaxLength(200),
                Arbitraries.integers().between(0, 255),
                Arbitraries.of(true, false),
                Arbitraries.longs(),
                Arbitraries.of(true, false, null)
        ).as((cmd, out, exit, cancelled, ts, exclude) ->
                new BashExecutionMessage(cmd, out, exit, cancelled, false, null, ts,
                        exclude != null ? exclude : null));
    }

    private Arbitrary<AgentMessage> customMessageArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(30),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(100),
                Arbitraries.of(true, false),
                Arbitraries.longs()
        ).as((type, content, display, ts) ->
                new CustomMessage(type, content, display, null, ts));
    }

    private Arbitrary<AgentMessage> branchSummaryArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(200),
                Arbitraries.strings().alpha().ofLength(8),
                Arbitraries.longs()
        ).as(BranchSummaryMessage::new);
    }

    private Arbitrary<AgentMessage> compactionSummaryArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(200),
                Arbitraries.integers().between(0, 100000),
                Arbitraries.longs()
        ).as(CompactionSummaryMessage::new);
    }
}
