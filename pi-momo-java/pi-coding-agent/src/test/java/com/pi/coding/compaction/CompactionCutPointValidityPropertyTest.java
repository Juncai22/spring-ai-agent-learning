package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import com.pi.coding.session.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.Assertions;

import java.time.Instant;
import java.util.*;

/**
 * Property test: Compaction Cut Point Validity
 *
 * <p><b>Property 3: Compaction Cut Point Validity</b>
 * <p>Verifies that findCutPoint returns a cut point that never points to a toolResult message.
 *
 * <p><b>Validates: Requirements 3.4, 3.5</b>
 */
class CompactionCutPointValidityPropertyTest {

    /**
     * Property: findCutPoint never returns an index pointing to a toolResult message.
     *
     * <p><b>Validates: Requirements 3.4, 3.5</b>
     */
    @Property(tries = 100)
    void findCutPoint_neverReturnsToolResultIndex(
            @ForAll("sessionEntriesWithMessages") List<SessionEntry> entries,
            @ForAll @IntRange(min = 1000, max = 50000) int keepRecentTokens
    ) {
        if (entries.isEmpty()) {
            return;
        }

        // Find cut point
        CutPointResult result = Compaction.findCutPoint(entries, 0, entries.size(), keepRecentTokens);

        // Verify the cut point is valid
        int cutIndex = result.firstKeptEntryIndex();
        Assertions.assertThat(cutIndex).isGreaterThanOrEqualTo(0);
        Assertions.assertThat(cutIndex).isLessThan(entries.size());

        // Verify the entry at cut point is NOT a toolResult
        SessionEntry cutEntry = entries.get(cutIndex);
        if (cutEntry instanceof SessionMessageEntry sme) {
            String role = sme.message().role();
            Assertions.assertThat(role)
                    .as("Cut point should never be at a toolResult message")
                    .isNotEqualTo("toolResult");
        }
    }

    /**
     * Property: All valid cut points returned by findValidCutPoints are not toolResult.
     *
     * <p><b>Validates: Requirements 3.4, 3.5</b>
     */
    @Property(tries = 100)
    void findValidCutPoints_neverIncludesToolResult(
            @ForAll("sessionEntriesWithMessages") List<SessionEntry> entries
    ) {
        if (entries.isEmpty()) {
            return;
        }

        // Get all valid cut points
        List<Integer> cutPoints = Compaction.findValidCutPoints(entries, 0, entries.size());

        // Verify none of them are toolResult
        for (int index : cutPoints) {
            SessionEntry entry = entries.get(index);
            if (entry instanceof SessionMessageEntry sme) {
                String role = sme.message().role();
                Assertions.assertThat(role)
                        .as("Valid cut point at index %d should not be toolResult", index)
                        .isNotEqualTo("toolResult");
            }
        }
    }

    /**
     * Property: findCutPoint returns valid cut points for user, assistant, custom, bashExecution messages.
     *
     * <p><b>Validates: Requirements 3.4, 3.5</b>
     */
    @Property(tries = 100)
    void findCutPoint_returnsValidMessageTypes(
            @ForAll("sessionEntriesWithMessages") List<SessionEntry> entries,
            @ForAll @IntRange(min = 1000, max = 50000) int keepRecentTokens
    ) {
        if (entries.isEmpty()) {
            return;
        }

        CutPointResult result = Compaction.findCutPoint(entries, 0, entries.size(), keepRecentTokens);
        int cutIndex = result.firstKeptEntryIndex();

        SessionEntry cutEntry = entries.get(cutIndex);

        // Valid cut points are: SessionMessageEntry (non-toolResult), BranchSummaryEntry, CustomMessageEntry
        // or non-message entries (which are skipped during cut point finding)
        if (cutEntry instanceof SessionMessageEntry sme) {
            String role = sme.message().role();
            Assertions.assertThat(role)
                    .as("Cut point message role should be valid")
                    .isIn("user", "assistant", "custom", "bashExecution", "branchSummary", "compactionSummary");
        } else if (cutEntry instanceof BranchSummaryEntry<?> || cutEntry instanceof CustomMessageEntry<?>) {
            // These are valid cut points
            Assertions.assertThat(true).isTrue();
        }
        // Other entry types (ThinkingLevelChange, ModelChange, etc.) are not cut points
        // but the algorithm may land on them when scanning backwards
    }

    // =========================================================================
    // Arbitraries
    // =========================================================================

    @Provide
    Arbitrary<List<SessionEntry>> sessionEntriesWithMessages() {
        return Arbitraries.integers().between(3, 20).flatMap(size -> {
            List<Arbitrary<SessionEntry>> entryArbitraries = new ArrayList<>();

            String prevId = null;
            for (int i = 0; i < size; i++) {
                final String parentId = prevId;
                final String id = "entry-" + i;
                prevId = id;

                // Generate a mix of message types
                Arbitrary<SessionEntry> entryArb = Arbitraries.of(
                        "user", "assistant", "toolResult", "custom", "bashExecution"
                ).map(role -> createMessageEntry(id, parentId, role));

                entryArbitraries.add(entryArb);
            }

            return Combinators.combine(entryArbitraries).as(entries -> entries);
        });
    }

    private SessionEntry createMessageEntry(String id, String parentId, String role) {
        String timestamp = Instant.now().toString();
        AgentMessage message = createAgentMessage(role);
        return SessionMessageEntry.create(id, parentId, timestamp, message);
    }

    private AgentMessage createAgentMessage(String role) {
        long timestamp = System.currentTimeMillis();

        return switch (role) {
            case "user" -> {
                UserMessage userMsg = new UserMessage("Test user message", timestamp);
                yield MessageAdapter.wrap(userMsg);
            }
            case "assistant" -> {
                AssistantMessage assistantMsg = AssistantMessage.builder()
                        .content(List.of(new TextContent("Test assistant response")))
                        .timestamp(timestamp)
                        .build();
                yield MessageAdapter.wrap(assistantMsg);
            }
            case "toolResult" -> {
                ToolResultMessage toolResultMsg = new ToolResultMessage(
                        "tool-call-1",
                        "read",
                        List.of(new TextContent("Tool result content")),
                        null,
                        false,
                        timestamp
                );
                yield MessageAdapter.wrap(toolResultMsg);
            }
            case "custom" -> new CustomAgentMessage("custom", "Custom message content", timestamp);
            case "bashExecution" -> new BashExecutionMessage("ls -la", "file1\nfile2", timestamp);
            default -> {
                UserMessage defaultMsg = new UserMessage("Default message", timestamp);
                yield MessageAdapter.wrap(defaultMsg);
            }
        };
    }

    /**
     * Custom agent message for testing.
     */
    private record CustomAgentMessage(String customType, String content, long timestamp) implements AgentMessage {
        @Override
        public String role() {
            return "custom";
        }

        @Override
        public String toString() {
            return content;
        }
    }

    /**
     * Bash execution message for testing.
     */
    private record BashExecutionMessage(String command, String output, long timestamp) implements AgentMessage {
        @Override
        public String role() {
            return "bashExecution";
        }

        @Override
        public String toString() {
            return command + "\n" + output;
        }
    }
}
