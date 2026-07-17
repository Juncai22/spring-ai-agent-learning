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
 * Property test: Branch Summary Collection
 *
 * <p><b>Property 5: Branch Summary Collection</b>
 * <p>Verifies that collectEntriesForBranchSummary returns exactly the entries
 * from fromId to the common ancestor between fromId and toId.
 *
 * <p><b>Validates: Requirements 4.1, 4.2</b>
 */
class BranchSummaryCollectionPropertyTest {

    /**
     * Property: Collected entries are exactly those from fromId to common ancestor.
     *
     * <p>For any valid session tree with fromId and toId:
     * <ul>
     *   <li>The collected entries should be in chronological order (root to leaf)</li>
     *   <li>The first entry should be a child of the common ancestor (or the first entry if no common ancestor)</li>
     *   <li>The last entry should be the fromId entry</li>
     *   <li>All entries should form a valid path (each entry's parentId points to the previous entry)</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 100)
    void collectEntriesForBranchSummary_returnsExactEntriesFromFromIdToCommonAncestor(
            @ForAll("sessionTreeWithBranches") SessionTreeTestData testData
    ) {
        SessionManager sessionManager = testData.sessionManager();
        String fromId = testData.fromId();
        String toId = testData.toId();

        // Collect entries for branch summary
        BranchSummarization.CollectEntriesResult result =
                BranchSummarization.collectEntriesForBranchSummary(sessionManager, fromId, toId);

        List<SessionEntry> collectedEntries = result.entries();
        String commonAncestorId = result.commonAncestorId();

        // If fromId is null, should return empty
        if (fromId == null) {
            Assertions.assertThat(collectedEntries).isEmpty();
            return;
        }

        // Verify entries are in chronological order (root to leaf)
        // Each entry's parentId should be the previous entry's id (or common ancestor for first)
        for (int i = 0; i < collectedEntries.size(); i++) {
            SessionEntry entry = collectedEntries.get(i);

            if (i == 0) {
                // First entry's parent should be the common ancestor
                Assertions.assertThat(entry.parentId())
                        .as("First collected entry's parent should be common ancestor")
                        .isEqualTo(commonAncestorId);
            } else {
                // Each subsequent entry's parent should be the previous entry
                SessionEntry prevEntry = collectedEntries.get(i - 1);
                Assertions.assertThat(entry.parentId())
                        .as("Entry at index %d should have parent equal to previous entry's id", i)
                        .isEqualTo(prevEntry.id());
            }
        }

        // Verify the last entry is the fromId entry
        if (!collectedEntries.isEmpty()) {
            SessionEntry lastEntry = collectedEntries.get(collectedEntries.size() - 1);
            Assertions.assertThat(lastEntry.id())
                    .as("Last collected entry should be the fromId entry")
                    .isEqualTo(fromId);
        }

        // Verify all collected entries are on the path from fromId to root
        Set<String> fromPath = new HashSet<>();
        for (SessionEntry entry : sessionManager.getBranch(fromId)) {
            fromPath.add(entry.id());
        }

        for (SessionEntry entry : collectedEntries) {
            Assertions.assertThat(fromPath)
                    .as("Collected entry %s should be on the path from fromId to root", entry.id())
                    .contains(entry.id());
        }

        // Verify common ancestor is on both paths
        if (commonAncestorId != null) {
            Set<String> toPath = new HashSet<>();
            for (SessionEntry entry : sessionManager.getBranch(toId)) {
                toPath.add(entry.id());
            }

            Assertions.assertThat(fromPath)
                    .as("Common ancestor should be on fromId path")
                    .contains(commonAncestorId);
            Assertions.assertThat(toPath)
                    .as("Common ancestor should be on toId path")
                    .contains(commonAncestorId);
        }
    }

    /**
     * Property: Collected entries do not include the common ancestor.
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 100)
    void collectEntriesForBranchSummary_doesNotIncludeCommonAncestor(
            @ForAll("sessionTreeWithBranches") SessionTreeTestData testData
    ) {
        SessionManager sessionManager = testData.sessionManager();
        String fromId = testData.fromId();
        String toId = testData.toId();

        BranchSummarization.CollectEntriesResult result =
                BranchSummarization.collectEntriesForBranchSummary(sessionManager, fromId, toId);

        String commonAncestorId = result.commonAncestorId();

        if (commonAncestorId != null) {
            Set<String> collectedIds = new HashSet<>();
            for (SessionEntry entry : result.entries()) {
                collectedIds.add(entry.id());
            }

            Assertions.assertThat(collectedIds)
                    .as("Collected entries should not include the common ancestor")
                    .doesNotContain(commonAncestorId);
        }
    }

    /**
     * Property: Empty result when fromId is null.
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 50)
    void collectEntriesForBranchSummary_returnsEmptyWhenFromIdIsNull(
            @ForAll("sessionTreeWithBranches") SessionTreeTestData testData
    ) {
        SessionManager sessionManager = testData.sessionManager();
        String toId = testData.toId();

        BranchSummarization.CollectEntriesResult result =
                BranchSummarization.collectEntriesForBranchSummary(sessionManager, null, toId);

        Assertions.assertThat(result.entries())
                .as("Should return empty entries when fromId is null")
                .isEmpty();
        Assertions.assertThat(result.commonAncestorId())
                .as("Should return null common ancestor when fromId is null")
                .isNull();
    }

    /**
     * Property: When fromId equals toId, should return empty (no entries to summarize).
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 50)
    void collectEntriesForBranchSummary_returnsEmptyWhenFromIdEqualsToId(
            @ForAll("sessionTreeWithBranches") SessionTreeTestData testData
    ) {
        SessionManager sessionManager = testData.sessionManager();
        String fromId = testData.fromId();

        if (fromId == null) return;

        BranchSummarization.CollectEntriesResult result =
                BranchSummarization.collectEntriesForBranchSummary(sessionManager, fromId, fromId);

        // When fromId == toId, the common ancestor is fromId itself
        // So there should be no entries between fromId and the common ancestor
        Assertions.assertThat(result.entries())
                .as("Should return empty entries when fromId equals toId")
                .isEmpty();
    }

    /**
     * Property: Collected entry count equals path length from fromId to common ancestor.
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 100)
    void collectEntriesForBranchSummary_entryCountMatchesPathLength(
            @ForAll("sessionTreeWithBranches") SessionTreeTestData testData
    ) {
        SessionManager sessionManager = testData.sessionManager();
        String fromId = testData.fromId();
        String toId = testData.toId();

        if (fromId == null) return;

        BranchSummarization.CollectEntriesResult result =
                BranchSummarization.collectEntriesForBranchSummary(sessionManager, fromId, toId);

        // Count entries from fromId to common ancestor manually
        int expectedCount = 0;
        String current = fromId;
        String commonAncestorId = result.commonAncestorId();

        while (current != null && !current.equals(commonAncestorId)) {
            SessionEntry entry = sessionManager.getEntry(current);
            if (entry == null) break;
            expectedCount++;
            current = entry.parentId();
        }

        Assertions.assertThat(result.entries().size())
                .as("Collected entry count should match path length from fromId to common ancestor")
                .isEqualTo(expectedCount);
    }

    // =========================================================================
    // Test Data Types
    // =========================================================================

    /**
     * Test data containing a session manager with a tree structure and two entry IDs.
     */
    record SessionTreeTestData(
            SessionManager sessionManager,
            String fromId,
            String toId,
            List<String> allEntryIds
    ) {}

    // =========================================================================
    // Arbitraries
    // =========================================================================

    @Provide
    Arbitrary<SessionTreeTestData> sessionTreeWithBranches() {
        return Arbitraries.integers().between(5, 15).flatMap(size -> {
            // Create an in-memory session manager
            SessionManager sessionManager = SessionManager.inMemory("/test/cwd");

            List<String> entryIds = new ArrayList<>();
            Map<String, String> parentMap = new HashMap<>();

            // Build a tree structure with potential branches
            // First, create a linear chain
            String prevId = null;
            for (int i = 0; i < size; i++) {
                AgentMessage message = createTestMessage(i);
                String entryId = sessionManager.appendMessage(message);
                entryIds.add(entryId);
                parentMap.put(entryId, prevId);
                prevId = entryId;
            }

            // Create a branch by going back to an earlier entry and adding more
            if (size > 3) {
                // Pick a branch point (somewhere in the middle)
                int branchPoint = size / 2;
                String branchParentId = entryIds.get(branchPoint);

                // Set leaf to branch point to create a branch
                sessionManager.setLeaf(branchParentId);

                // Add entries on the branch
                int branchSize = Math.min(3, size - branchPoint);
                for (int i = 0; i < branchSize; i++) {
                    AgentMessage message = createTestMessage(size + i);
                    String entryId = sessionManager.appendMessage(message);
                    entryIds.add(entryId);
                }
            }

            // Select fromId and toId from different branches or positions
            return Arbitraries.of(entryIds).tuple2().map(tuple -> {
                String fromId = tuple.get1();
                String toId = tuple.get2();
                return new SessionTreeTestData(sessionManager, fromId, toId, entryIds);
            });
        });
    }

    private AgentMessage createTestMessage(int index) {
        long timestamp = System.currentTimeMillis() + index;

        // Alternate between user and assistant messages
        if (index % 2 == 0) {
            UserMessage userMsg = new UserMessage("Test user message " + index, timestamp);
            return MessageAdapter.wrap(userMsg);
        } else {
            AssistantMessage assistantMsg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Test assistant response " + index)))
                    .timestamp(timestamp)
                    .build();
            return MessageAdapter.wrap(assistantMsg);
        }
    }
}
