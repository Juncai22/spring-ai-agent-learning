package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.assertj.core.api.Assertions;

import java.util.*;

/**
 * Property test: Token Estimation Consistency
 *
 * <p><b>Property 4: Token Estimation Consistency</b>
 * <p>Verifies that estimateTokens always returns the same value for the same message list (deterministic).
 *
 * <p><b>Validates: Requirement 3.2</b>
 */
class TokenEstimationConsistencyPropertyTest {

    /**
     * Property: estimateTokens returns the same value for the same message list.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 100)
    void estimateTokens_isDeterministic(
            @ForAll("agentMessageList") List<AgentMessage> messages
    ) {
        // Call estimateTokens multiple times
        int result1 = CompactionUtils.estimateTokens(messages);
        int result2 = CompactionUtils.estimateTokens(messages);
        int result3 = CompactionUtils.estimateTokens(messages);

        // All results should be identical
        Assertions.assertThat(result1)
                .as("estimateTokens should be deterministic")
                .isEqualTo(result2)
                .isEqualTo(result3);
    }

    /**
     * Property: estimateTokens for a single message is deterministic.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 100)
    void estimateTokens_singleMessage_isDeterministic(
            @ForAll("agentMessage") AgentMessage message
    ) {
        int result1 = CompactionUtils.estimateTokens(message);
        int result2 = CompactionUtils.estimateTokens(message);
        int result3 = CompactionUtils.estimateTokens(message);

        Assertions.assertThat(result1)
                .as("estimateTokens for single message should be deterministic")
                .isEqualTo(result2)
                .isEqualTo(result3);
    }

    /**
     * Property: estimateTokens returns non-negative values.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 100)
    void estimateTokens_returnsNonNegative(
            @ForAll("agentMessageList") List<AgentMessage> messages
    ) {
        int result = CompactionUtils.estimateTokens(messages);

        Assertions.assertThat(result)
                .as("estimateTokens should return non-negative value")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * Property: estimateTokens for single message returns at least 1 for non-null messages.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 100)
    void estimateTokens_singleMessage_returnsAtLeastOne(
            @ForAll("agentMessage") AgentMessage message
    ) {
        int result = CompactionUtils.estimateTokens(message);

        Assertions.assertThat(result)
                .as("estimateTokens for non-null message should return at least 1")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * Property: estimateTokens for list equals sum of individual estimates.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 100)
    void estimateTokens_listEqualsSumOfIndividual(
            @ForAll("agentMessageList") List<AgentMessage> messages
    ) {
        int listResult = CompactionUtils.estimateTokens(messages);

        int sumResult = 0;
        for (AgentMessage message : messages) {
            sumResult += CompactionUtils.estimateTokens(message);
        }

        Assertions.assertThat(listResult)
                .as("estimateTokens for list should equal sum of individual estimates")
                .isEqualTo(sumResult);
    }

    /**
     * Property: estimateTokens uses chars/4 heuristic (approximately).
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Property(tries = 50)
    void estimateTokens_usesCharsDiv4Heuristic(
            @ForAll @StringLength(min = 10, max = 1000) String content
    ) {
        long timestamp = System.currentTimeMillis();
        UserMessage userMsg = new UserMessage(content, timestamp);
        AgentMessage message = MessageAdapter.wrap(userMsg);

        int result = CompactionUtils.estimateTokens(message);

        // Expected: ceil(content.length / 4)
        int expected = (int) Math.ceil(content.length() / 4.0);

        Assertions.assertThat(result)
                .as("estimateTokens should use chars/4 heuristic")
                .isEqualTo(expected);
    }

    /**
     * Property: estimateTokens for empty list returns 0.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Example
    void estimateTokens_emptyList_returnsZero() {
        int result = CompactionUtils.estimateTokens(List.of());

        Assertions.assertThat(result)
                .as("estimateTokens for empty list should return 0")
                .isEqualTo(0);
    }

    /**
     * Property: estimateTokens for null list returns 0.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Example
    void estimateTokens_nullList_returnsZero() {
        int result = CompactionUtils.estimateTokens((List<AgentMessage>) null);

        Assertions.assertThat(result)
                .as("estimateTokens for null list should return 0")
                .isEqualTo(0);
    }

    /**
     * Property: estimateTokens for null message returns 0.
     *
     * <p><b>Validates: Requirement 3.2</b>
     */
    @Example
    void estimateTokens_nullMessage_returnsZero() {
        int result = CompactionUtils.estimateTokens((AgentMessage) null);

        Assertions.assertThat(result)
                .as("estimateTokens for null message should return 0")
                .isEqualTo(0);
    }

    // =========================================================================
    // Arbitraries
    // =========================================================================

    @Provide
    Arbitrary<AgentMessage> agentMessage() {
        return Arbitraries.oneOf(
                userMessages(),
                assistantMessages(),
                toolResultMessages(),
                customMessages()
        );
    }

    @Provide
    Arbitrary<List<AgentMessage>> agentMessageList() {
        return agentMessage().list().ofMinSize(0).ofMaxSize(20);
    }

    private Arbitrary<AgentMessage> userMessages() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(500).map(content -> {
            long timestamp = System.currentTimeMillis();
            UserMessage userMsg = new UserMessage(content, timestamp);
            return MessageAdapter.wrap(userMsg);
        });
    }

    private Arbitrary<AgentMessage> assistantMessages() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(500).map(content -> {
            long timestamp = System.currentTimeMillis();
            AssistantMessage assistantMsg = AssistantMessage.builder()
                    .content(List.of(new TextContent(content)))
                    .timestamp(timestamp)
                    .build();
            return MessageAdapter.wrap(assistantMsg);
        });
    }

    private Arbitrary<AgentMessage> toolResultMessages() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(500).map(content -> {
            long timestamp = System.currentTimeMillis();
            ToolResultMessage toolResultMsg = new ToolResultMessage(
                    "tool-call-" + UUID.randomUUID().toString().substring(0, 8),
                    "read",
                    List.of(new TextContent(content)),
                    null,
                    false,
                    timestamp
            );
            return MessageAdapter.wrap(toolResultMsg);
        });
    }

    private Arbitrary<AgentMessage> customMessages() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(500).map(content -> {
            long timestamp = System.currentTimeMillis();
            return new TestCustomMessage(content, timestamp);
        });
    }

    /**
     * Test custom message implementation.
     */
    private record TestCustomMessage(String content, long timestamp) implements AgentMessage {
        @Override
        public String role() {
            return "custom";
        }

        @Override
        public String toString() {
            return content;
        }
    }
}
