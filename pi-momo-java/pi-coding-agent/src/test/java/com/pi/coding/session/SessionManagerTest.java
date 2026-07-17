package com.pi.coding.session;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.UserMessage;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SessionManager.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.17</b>
 */
class SessionManagerTest {

    @TempDir
    Path tempDir;

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = SessionManager.inMemory("/test/cwd");
    }

    // =========================================================================
    // Task 3.1: Basic Structure Tests
    // =========================================================================

    @Test
    void create_shouldCreateSessionManagerWithPersistence() {
        SessionManager sm = SessionManager.create("/test/cwd", tempDir);

        assertThat(sm.isPersisted()).isTrue();
        assertThat(sm.getCwd()).isEqualTo("/test/cwd");
        assertThat(sm.getSessionDir()).isEqualTo(tempDir);
        assertThat(sm.getSessionId()).isNotNull();
        assertThat(sm.getSessionFile()).isNotNull();
    }

    @Test
    void inMemory_shouldCreateSessionManagerWithoutPersistence() {
        SessionManager sm = SessionManager.inMemory("/test/cwd");

        assertThat(sm.isPersisted()).isFalse();
        assertThat(sm.getCwd()).isEqualTo("/test/cwd");
        assertThat(sm.getSessionFile()).isNull();
    }

    @Test
    void getHeader_shouldReturnSessionHeader() {
        SessionHeader header = manager.getHeader();

        assertThat(header).isNotNull();
        assertThat(header.type()).isEqualTo("session");
        assertThat(header.version()).isEqualTo(SessionManager.CURRENT_SESSION_VERSION);
        assertThat(header.id()).isEqualTo(manager.getSessionId());
        assertThat(header.cwd()).isEqualTo("/test/cwd");
        assertThat(header.timestamp()).isNotNull();
    }

    @Test
    void getEntries_shouldReturnEmptyListForNewSession() {
        List<SessionEntry> entries = manager.getEntries();

        assertThat(entries).isEmpty();
    }

    @Test
    void getLeafId_shouldReturnNullForNewSession() {
        assertThat(manager.getLeafId()).isNull();
    }

    @Test
    void getLeafEntry_shouldReturnNullForNewSession() {
        assertThat(manager.getLeafEntry()).isNull();
    }

    // =========================================================================
    // Task 3.2: Session Creation and Loading Tests
    // =========================================================================

    @Test
    void newSession_shouldCreateNewSessionWithDefaults() {
        Path sessionFile = manager.newSession(null);

        assertThat(sessionFile).isNull(); // in-memory mode
        assertThat(manager.getSessionId()).isNotNull();
        assertThat(manager.getEntries()).isEmpty();
        assertThat(manager.getLeafId()).isNull();
    }

    @Test
    void newSession_shouldCreateNewSessionWithCustomId() {
        String customId = "custom-session-id";
        manager.newSession(NewSessionOptions.withId(customId));

        assertThat(manager.getSessionId()).isEqualTo(customId);
    }

    @Test
    void newSession_shouldCreateNewSessionWithParentSession() {
        String parentPath = "/path/to/parent.jsonl";
        manager.newSession(NewSessionOptions.withParent(parentPath));

        SessionHeader header = manager.getHeader();
        assertThat(header.parentSession()).isEqualTo(parentPath);
    }

    @Test
    void newSession_withPersistence_shouldCreateSessionFile() {
        SessionManager sm = SessionManager.create("/test/cwd", tempDir);
        Path sessionFile = sm.newSession(null);

        assertThat(sessionFile).isNotNull();
        assertThat(sessionFile.getParent()).isEqualTo(tempDir);
        assertThat(sessionFile.toString()).endsWith(".jsonl");
    }

    @Test
    void setSessionFile_shouldLoadExistingSession() throws IOException {
        // Create a session file
        String sessionId = UUID.randomUUID().toString();
        String timestamp = "2024-01-01T00:00:00Z";
        SessionHeader header = SessionHeader.create(sessionId, timestamp, "/test/cwd", null);

        Path sessionFile = tempDir.resolve("test-session.jsonl");
        Files.writeString(sessionFile, PiAiJson.MAPPER.writeValueAsString(header) + "\n");

        // Load the session
        SessionManager sm = SessionManager.inMemory("/test/cwd");
        sm.setSessionFile(sessionFile);

        assertThat(sm.getSessionId()).isEqualTo(sessionId);
        assertThat(sm.getHeader().timestamp()).isEqualTo(timestamp);
    }

    @Test
    void setSessionFile_shouldHandleEmptyFile() throws IOException {
        Path sessionFile = tempDir.resolve("empty-session.jsonl");
        Files.writeString(sessionFile, "");

        SessionManager sm = SessionManager.create("/test/cwd", tempDir);
        sm.setSessionFile(sessionFile);

        // Should create a new session
        assertThat(sm.getSessionId()).isNotNull();
        assertThat(sm.getHeader()).isNotNull();
    }

    @Test
    void setSessionFile_shouldHandleNonExistentFile() {
        Path sessionFile = tempDir.resolve("non-existent.jsonl");

        SessionManager sm = SessionManager.create("/test/cwd", tempDir);
        sm.setSessionFile(sessionFile);

        // Should create a new session with the specified path
        assertThat(sm.getSessionFile()).isEqualTo(sessionFile);
        assertThat(sm.getSessionId()).isNotNull();
    }

    @Test
    void open_shouldOpenExistingSessionFile() throws IOException {
        // Create a session file
        String sessionId = UUID.randomUUID().toString();
        String timestamp = "2024-01-01T00:00:00Z";
        SessionHeader header = SessionHeader.create(sessionId, timestamp, "/original/cwd", null);

        Path sessionFile = tempDir.resolve("existing-session.jsonl");
        Files.writeString(sessionFile, PiAiJson.MAPPER.writeValueAsString(header) + "\n");

        // Open the session
        SessionManager sm = SessionManager.open(sessionFile, tempDir);

        assertThat(sm.getSessionId()).isEqualTo(sessionId);
        assertThat(sm.getCwd()).isEqualTo("/original/cwd");
        assertThat(sm.getSessionFile()).isEqualTo(sessionFile);
    }

    // =========================================================================
    // Version Migration Tests
    // =========================================================================

    @Test
    void migrateToCurrentVersion_shouldReturnFalseForCurrentVersion() throws IOException {
        // Create a v3 session file
        SessionHeader header = SessionHeader.create(
                UUID.randomUUID().toString(),
                "2024-01-01T00:00:00Z",
                "/test/cwd",
                null
        );

        Path sessionFile = tempDir.resolve("v3-session.jsonl");
        Files.writeString(sessionFile, PiAiJson.MAPPER.writeValueAsString(header) + "\n");

        List<Object> entries = SessionManager.loadEntriesFromFile(sessionFile);
        boolean migrated = SessionManager.migrateToCurrentVersion(entries);

        assertThat(migrated).isFalse();
    }

    @Test
    void migrateV1ToV2_shouldAddIdsAndParentIds() {
        // Create v1 entries (no version, no id/parentId)
        SessionHeader v1Header = new SessionHeader("session", 0, "test-id", "2024-01-01T00:00:00Z", "/test/cwd", null);

        List<Object> entries = new java.util.ArrayList<>();
        entries.add(v1Header);

        SessionManager.migrateV1ToV2(entries);

        // Header should be updated to v2
        SessionHeader migratedHeader = (SessionHeader) entries.get(0);
        assertThat(migratedHeader.version()).isEqualTo(2);
    }

    @Test
    void migrateV2ToV3_shouldUpdateVersion() {
        // Create v2 header
        SessionHeader v2Header = new SessionHeader("session", 2, "test-id", "2024-01-01T00:00:00Z", "/test/cwd", null);

        List<Object> entries = new java.util.ArrayList<>();
        entries.add(v2Header);

        SessionManager.migrateV2ToV3(entries);

        // Header should be updated to v3
        SessionHeader migratedHeader = (SessionHeader) entries.get(0);
        assertThat(migratedHeader.version()).isEqualTo(3);
    }

    // =========================================================================
    // JSONL File I/O Tests
    // =========================================================================

    @Test
    void loadEntriesFromFile_shouldReturnEmptyListForNonExistentFile() {
        Path nonExistent = tempDir.resolve("non-existent.jsonl");

        List<Object> entries = SessionManager.loadEntriesFromFile(nonExistent);

        assertThat(entries).isEmpty();
    }

    @Test
    void loadEntriesFromFile_shouldParseValidSessionFile() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);

        Path sessionFile = tempDir.resolve("valid-session.jsonl");
        Files.writeString(sessionFile, PiAiJson.MAPPER.writeValueAsString(header) + "\n");

        List<Object> entries = SessionManager.loadEntriesFromFile(sessionFile);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).isInstanceOf(SessionHeader.class);
        assertThat(((SessionHeader) entries.get(0)).id()).isEqualTo(sessionId);
    }

    @Test
    void loadEntriesFromFile_shouldSkipMalformedLines() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);

        Path sessionFile = tempDir.resolve("malformed-session.jsonl");
        String content = PiAiJson.MAPPER.writeValueAsString(header) + "\n" +
                "invalid json line\n" +
                "{\"type\":\"message\",\"id\":\"abc\",\"parentId\":null,\"timestamp\":\"2024-01-01T00:00:00Z\"}\n";
        Files.writeString(sessionFile, content);

        List<Object> entries = SessionManager.loadEntriesFromFile(sessionFile);

        // Should have header, skip malformed line
        assertThat(entries).hasSizeGreaterThanOrEqualTo(1);
        assertThat(entries.get(0)).isInstanceOf(SessionHeader.class);
    }

    @Test
    void loadEntriesFromFile_shouldReturnEmptyForInvalidHeader() throws IOException {
        Path sessionFile = tempDir.resolve("invalid-header.jsonl");
        Files.writeString(sessionFile, "{\"type\":\"not-session\",\"id\":\"test\"}\n");

        List<Object> entries = SessionManager.loadEntriesFromFile(sessionFile);

        assertThat(entries).isEmpty();
    }

    // =========================================================================
    // Internal Index Tests
    // =========================================================================

    @Test
    void setLeaf_shouldUpdateLeafPointer() throws IOException {
        // Create a session with entries
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);
        ThinkingLevelChangeEntry entry1 = ThinkingLevelChangeEntry.create("entry1", null, "2024-01-01T00:00:01Z", "low");
        ThinkingLevelChangeEntry entry2 = ThinkingLevelChangeEntry.create("entry2", "entry1", "2024-01-01T00:00:02Z", "high");

        Path sessionFile = tempDir.resolve("multi-entry-session.jsonl");
        StringBuilder content = new StringBuilder();
        content.append(PiAiJson.MAPPER.writeValueAsString(header)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry1)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry2)).append("\n");
        Files.writeString(sessionFile, content.toString());

        SessionManager sm = SessionManager.open(sessionFile, tempDir);

        // Initially leaf should be the last entry
        assertThat(sm.getLeafId()).isEqualTo("entry2");

        // Set leaf to first entry
        sm.setLeaf("entry1");
        assertThat(sm.getLeafId()).isEqualTo("entry1");
    }

    @Test
    void setLeaf_shouldThrowForNonExistentEntry() {
        assertThatThrownBy(() -> manager.setLeaf("non-existent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void setLeaf_shouldAllowNullToResetLeaf() {
        manager.setLeaf(null);
        assertThat(manager.getLeafId()).isNull();
    }

    @Test
    void getEntry_shouldReturnEntryById() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);
        ThinkingLevelChangeEntry entry = ThinkingLevelChangeEntry.create("test-entry", null, "2024-01-01T00:00:01Z", "medium");

        Path sessionFile = tempDir.resolve("entry-lookup-session.jsonl");
        StringBuilder content = new StringBuilder();
        content.append(PiAiJson.MAPPER.writeValueAsString(header)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry)).append("\n");
        Files.writeString(sessionFile, content.toString());

        SessionManager sm = SessionManager.open(sessionFile, tempDir);

        SessionEntry found = sm.getEntry("test-entry");
        assertThat(found).isNotNull();
        assertThat(found).isInstanceOf(ThinkingLevelChangeEntry.class);
        assertThat(((ThinkingLevelChangeEntry) found).thinkingLevel()).isEqualTo("medium");
    }

    @Test
    void getEntry_shouldReturnNullForNonExistentId() {
        assertThat(manager.getEntry("non-existent")).isNull();
    }

    @Test
    void getLabel_shouldReturnLabelForEntry() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);
        ThinkingLevelChangeEntry entry = ThinkingLevelChangeEntry.create("target-entry", null, "2024-01-01T00:00:01Z", "low");
        LabelEntry label = LabelEntry.create("label-entry", "target-entry", "2024-01-01T00:00:02Z", "target-entry", "My Bookmark");

        Path sessionFile = tempDir.resolve("label-session.jsonl");
        StringBuilder content = new StringBuilder();
        content.append(PiAiJson.MAPPER.writeValueAsString(header)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(label)).append("\n");
        Files.writeString(sessionFile, content.toString());

        SessionManager sm = SessionManager.open(sessionFile, tempDir);

        assertThat(sm.getLabel("target-entry")).isEqualTo("My Bookmark");
    }

    @Test
    void getLabel_shouldReturnNullForUnlabeledEntry() {
        assertThat(manager.getLabel("any-id")).isNull();
    }

    // =========================================================================
    // Session Context Building Tests (Basic)
    // =========================================================================

    @Test
    void buildSessionContext_shouldReturnEmptyContextForNewSession() {
        SessionContext context = manager.buildSessionContext();

        assertThat(context.messages()).isEmpty();
        assertThat(context.thinkingLevel()).isEqualTo("off");
        assertThat(context.model()).isNull();
    }

    @Test
    void buildSessionContext_shouldExtractThinkingLevel() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);
        ThinkingLevelChangeEntry entry = ThinkingLevelChangeEntry.create("entry1", null, "2024-01-01T00:00:01Z", "high");

        Path sessionFile = tempDir.resolve("thinking-level-session.jsonl");
        StringBuilder content = new StringBuilder();
        content.append(PiAiJson.MAPPER.writeValueAsString(header)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry)).append("\n");
        Files.writeString(sessionFile, content.toString());

        SessionManager sm = SessionManager.open(sessionFile, tempDir);
        SessionContext context = sm.buildSessionContext();

        assertThat(context.thinkingLevel()).isEqualTo("high");
    }

    @Test
    void buildSessionContext_shouldExtractModelInfo() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SessionHeader header = SessionHeader.create(sessionId, "2024-01-01T00:00:00Z", "/test/cwd", null);
        ModelChangeEntry entry = ModelChangeEntry.create("entry1", null, "2024-01-01T00:00:01Z", "anthropic", "claude-3-opus");

        Path sessionFile = tempDir.resolve("model-change-session.jsonl");
        StringBuilder content = new StringBuilder();
        content.append(PiAiJson.MAPPER.writeValueAsString(header)).append("\n");
        content.append(PiAiJson.MAPPER.writeValueAsString(entry)).append("\n");
        Files.writeString(sessionFile, content.toString());

        SessionManager sm = SessionManager.open(sessionFile, tempDir);
        SessionContext context = sm.buildSessionContext();

        assertThat(context.model()).isNotNull();
        assertThat(context.model().provider()).isEqualTo("anthropic");
        assertThat(context.model().modelId()).isEqualTo("claude-3-opus");
    }

    // =========================================================================
    // Task 3.3: Entry Append Methods Tests
    // =========================================================================

    @Test
    void appendMessage_shouldCreateMessageEntry() {
        AgentMessage message = MessageAdapter.wrap(new UserMessage("user", "Hello", System.currentTimeMillis()));

        String id = manager.appendMessage(message);

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);
        assertThat(manager.getEntries()).hasSize(1);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(SessionMessageEntry.class);
        SessionMessageEntry msgEntry = (SessionMessageEntry) entry;
        assertThat(msgEntry.message()).isEqualTo(message);
        assertThat(msgEntry.parentId()).isNull(); // First entry has null parent
    }

    @Test
    void appendMessage_shouldChainEntries() {
        AgentMessage msg1 = MessageAdapter.wrap(new UserMessage("user", "First", System.currentTimeMillis()));
        AgentMessage msg2 = MessageAdapter.wrap(new UserMessage("user", "Second", System.currentTimeMillis()));

        String id1 = manager.appendMessage(msg1);
        String id2 = manager.appendMessage(msg2);

        assertThat(manager.getLeafId()).isEqualTo(id2);
        assertThat(manager.getEntries()).hasSize(2);

        SessionMessageEntry entry2 = (SessionMessageEntry) manager.getEntry(id2);
        assertThat(entry2.parentId()).isEqualTo(id1);
    }

    @Test
    void appendThinkingLevelChange_shouldCreateEntry() {
        String id = manager.appendThinkingLevelChange("high");

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(ThinkingLevelChangeEntry.class);
        ThinkingLevelChangeEntry tlcEntry = (ThinkingLevelChangeEntry) entry;
        assertThat(tlcEntry.thinkingLevel()).isEqualTo("high");
    }

    @Test
    void appendModelChange_shouldCreateEntry() {
        String id = manager.appendModelChange("openai", "gpt-4");

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(ModelChangeEntry.class);
        ModelChangeEntry mcEntry = (ModelChangeEntry) entry;
        assertThat(mcEntry.provider()).isEqualTo("openai");
        assertThat(mcEntry.modelId()).isEqualTo("gpt-4");
    }

    @Test
    void appendCompaction_shouldCreateEntry() {
        String id = manager.appendCompaction("Summary of conversation", "first-kept-id", 5000);

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(CompactionEntry.class);
        CompactionEntry<?> compEntry = (CompactionEntry<?>) entry;
        assertThat(compEntry.summary()).isEqualTo("Summary of conversation");
        assertThat(compEntry.firstKeptEntryId()).isEqualTo("first-kept-id");
        assertThat(compEntry.tokensBefore()).isEqualTo(5000);
    }

    @Test
    void appendCompaction_withDetails_shouldCreateEntry() {
        String id = manager.appendCompaction("Summary", "kept-id", 3000, "custom-details", true);

        CompactionEntry<?> entry = (CompactionEntry<?>) manager.getEntry(id);
        assertThat(entry.details()).isEqualTo("custom-details");
        assertThat(entry.fromHook()).isTrue();
    }

    @Test
    void appendBranchSummary_shouldCreateEntry() {
        String id = manager.appendBranchSummary("from-entry-id", "Branch summary text");

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(BranchSummaryEntry.class);
        BranchSummaryEntry<?> bsEntry = (BranchSummaryEntry<?>) entry;
        assertThat(bsEntry.fromId()).isEqualTo("from-entry-id");
        assertThat(bsEntry.summary()).isEqualTo("Branch summary text");
    }

    @Test
    void appendCustomEntry_shouldCreateEntry() {
        String id = manager.appendCustomEntry("my-extension", "custom-data");

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(CustomEntry.class);
        CustomEntry<?> customEntry = (CustomEntry<?>) entry;
        assertThat(customEntry.customType()).isEqualTo("my-extension");
        assertThat(customEntry.data()).isEqualTo("custom-data");
    }

    @Test
    void appendCustomMessageEntry_shouldCreateEntry() {
        String id = manager.appendCustomMessageEntry("hook-type", "Message content", true);

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(CustomMessageEntry.class);
        CustomMessageEntry<?> cmEntry = (CustomMessageEntry<?>) entry;
        assertThat(cmEntry.customType()).isEqualTo("hook-type");
        assertThat(cmEntry.content()).isEqualTo("Message content");
        assertThat(cmEntry.display()).isTrue();
    }

    @Test
    void appendLabelChange_shouldCreateEntryAndUpdateIndex() {
        // First add an entry to label
        String targetId = manager.appendThinkingLevelChange("low");

        // Add label
        String labelId = manager.appendLabelChange(targetId, "Important Point");

        assertThat(labelId).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(labelId);
        assertThat(manager.getLabel(targetId)).isEqualTo("Important Point");

        SessionEntry entry = manager.getEntry(labelId);
        assertThat(entry).isInstanceOf(LabelEntry.class);
        LabelEntry labelEntry = (LabelEntry) entry;
        assertThat(labelEntry.targetId()).isEqualTo(targetId);
        assertThat(labelEntry.label()).isEqualTo("Important Point");
    }

    @Test
    void appendLabelChange_withEmptyLabel_shouldClearLabel() {
        String targetId = manager.appendThinkingLevelChange("low");
        manager.appendLabelChange(targetId, "Initial Label");

        assertThat(manager.getLabel(targetId)).isEqualTo("Initial Label");

        // Clear the label
        manager.appendLabelChange(targetId, "");

        assertThat(manager.getLabel(targetId)).isNull();
    }

    @Test
    void appendSessionInfo_shouldCreateEntry() {
        String id = manager.appendSessionInfo("My Important Session");

        assertThat(id).isNotNull();
        assertThat(manager.getLeafId()).isEqualTo(id);

        SessionEntry entry = manager.getEntry(id);
        assertThat(entry).isInstanceOf(SessionInfoEntry.class);
        SessionInfoEntry siEntry = (SessionInfoEntry) entry;
        assertThat(siEntry.name()).isEqualTo("My Important Session");
    }

    // =========================================================================
    // Task 3.4: Tree Traversal Tests
    // =========================================================================

    @Test
    void getChildren_shouldReturnDirectChildren() {
        // Create a tree: root -> child1, child2
        String rootId = manager.appendThinkingLevelChange("low");

        manager.setLeaf(rootId);
        String child1Id = manager.appendThinkingLevelChange("medium");

        manager.setLeaf(rootId);
        String child2Id = manager.appendThinkingLevelChange("high");

        List<SessionEntry> children = manager.getChildren(rootId);

        assertThat(children).hasSize(2);
        assertThat(children.stream().map(SessionEntry::id))
                .containsExactlyInAnyOrder(child1Id, child2Id);
    }

    @Test
    void getChildren_shouldReturnEmptyForLeafNode() {
        String leafId = manager.appendThinkingLevelChange("low");

        List<SessionEntry> children = manager.getChildren(leafId);

        assertThat(children).isEmpty();
    }

    @Test
    void getChildren_withNullParent_shouldReturnRootEntries() {
        String rootId = manager.appendThinkingLevelChange("low");

        List<SessionEntry> rootEntries = manager.getChildren(null);

        assertThat(rootEntries).hasSize(1);
        assertThat(rootEntries.get(0).id()).isEqualTo(rootId);
    }

    @Test
    void getBranch_shouldReturnPathFromRootToEntry() {
        String id1 = manager.appendThinkingLevelChange("low");
        String id2 = manager.appendThinkingLevelChange("medium");
        String id3 = manager.appendThinkingLevelChange("high");

        List<SessionEntry> branch = manager.getBranch(id3);

        assertThat(branch).hasSize(3);
        assertThat(branch.get(0).id()).isEqualTo(id1);
        assertThat(branch.get(1).id()).isEqualTo(id2);
        assertThat(branch.get(2).id()).isEqualTo(id3);
    }

    @Test
    void getBranch_forRootEntry_shouldReturnSingleEntry() {
        String rootId = manager.appendThinkingLevelChange("low");

        List<SessionEntry> branch = manager.getBranch(rootId);

        assertThat(branch).hasSize(1);
        assertThat(branch.get(0).id()).isEqualTo(rootId);
    }

    @Test
    void getBranch_forNonExistentEntry_shouldReturnEmptyList() {
        List<SessionEntry> branch = manager.getBranch("non-existent");

        assertThat(branch).isEmpty();
    }

    // =========================================================================
    // Task 3.5: buildSessionContext Advanced Tests
    // =========================================================================

    @Test
    void buildSessionContext_shouldCollectMessagesFromPath() {
        AgentMessage msg1 = MessageAdapter.wrap(new UserMessage("user", "Hello", System.currentTimeMillis()));
        AgentMessage msg2 = MessageAdapter.wrap(new UserMessage("user", "World", System.currentTimeMillis()));

        manager.appendMessage(msg1);
        manager.appendMessage(msg2);

        SessionContext context = manager.buildSessionContext();

        assertThat(context.messages()).hasSize(2);
    }

    @Test
    void buildSessionContext_shouldUseLatestThinkingLevel() {
        manager.appendThinkingLevelChange("low");
        manager.appendThinkingLevelChange("medium");
        manager.appendThinkingLevelChange("high");

        SessionContext context = manager.buildSessionContext();

        assertThat(context.thinkingLevel()).isEqualTo("high");
    }

    @Test
    void buildSessionContext_shouldUseLatestModel() {
        manager.appendModelChange("anthropic", "claude-3-haiku");
        manager.appendModelChange("openai", "gpt-4");

        SessionContext context = manager.buildSessionContext();

        assertThat(context.model().provider()).isEqualTo("openai");
        assertThat(context.model().modelId()).isEqualTo("gpt-4");
    }

    @Test
    void buildSessionContext_withBranching_shouldFollowCorrectPath() {
        // Create tree:
        //   root (low) -> branch1 (medium)
        //              -> branch2 (high)
        String rootId = manager.appendThinkingLevelChange("low");

        manager.setLeaf(rootId);
        manager.appendThinkingLevelChange("medium");

        manager.setLeaf(rootId);
        String branch2Id = manager.appendThinkingLevelChange("high");

        // Leaf is at branch2
        assertThat(manager.getLeafId()).isEqualTo(branch2Id);

        SessionContext context = manager.buildSessionContext();

        // Should follow root -> branch2 path
        assertThat(context.thinkingLevel()).isEqualTo("high");
    }
}
