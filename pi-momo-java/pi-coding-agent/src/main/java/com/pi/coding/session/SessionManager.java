package com.pi.coding.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.pi.ai.core.util.PiAiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * 会话管理器：管理以追加式树形结构存储在 JSONL 文件中的对话会话。
 *
 * <p>会话管理器是会话持久化的核心组件，采用以下设计：
 *
 * <h3>树形结构</h3>
 * 每个会话条目（SessionEntry）有 id 和 parentId，形成树形结构。
 * "叶子"指针跟踪当前工作位置。追加条目时会创建当前叶子的子节点。
 * 分支操作将叶子指针移动到之前的条目，从而在不修改历史的情况下创建新分支。
 *
 * <h3>JSONL 文件格式</h3>
 * 会话存储为 JSONL（JSON Lines）文件，每行一个 JSON 对象。
 * 第一行是会话头（SessionHeader），包含版本号、ID、时间戳等元数据。
 * 后续行是会话条目（SessionEntry），支持多种类型：消息、模型变更、
 * 思考级别变更、压缩、分支摘要、自定义条目等。
 *
 * <h3>版本迁移</h3>
 * 支持会话文件版本迁移（当前版本 v3）：
 * <ul>
 *   <li>v1 → v2：添加 id/parentId 树形结构</li>
 *   <li>v2 → v3：重命名 hookMessage 角色为 custom</li>
 * </ul>
 *
 * <h3>上下文构建</h3>
 * 使用 {@link #buildSessionContext()} 构建发送给 LLM 的消息列表，
 * 处理压缩摘要和分支摘要，从当前叶子节点回溯到根节点。
 *
 * <p>验证需求：1.1, 1.2, 1.12-1.17
 */
public class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 当前会话格式版本号。
     */
    public static final int CURRENT_SESSION_VERSION = 3;

    /** 当前会话的唯一标识符 */
    private String sessionId = "";
    /** 当前会话文件的路径 */
    private Path sessionFile;
    /** 会话文件存储目录 */
    private final Path sessionDir;
    /** 创建会话时的工作目录 */
    private final String cwd;
    /** 是否启用文件持久化 */
    private final boolean persist;
    /** 是否已刷新到文件（延迟写入优化） */
    private boolean flushed = false;

    /** 文件条目列表，包括会话头和所有会话条目 */
    private final List<Object> fileEntries = new ArrayList<>();

    /** 内部索引：条目 ID → 条目对象 */
    private final Map<String, SessionEntry> byId = new HashMap<>();
    /** 内部索引：条目 ID → 标签文本 */
    private final Map<String, String> labelsById = new HashMap<>();
    /** 当前叶子条目 ID */
    private String leafId = null;

    // =========================================================================
    // 构造方法和工厂方法
    // =========================================================================

    /**
     * 私有构造方法，通过工厂方法创建实例。
     *
     * @param cwd         工作目录
     * @param sessionDir  会话目录
     * @param sessionFile 会话文件路径（可为 null）
     * @param persist     是否持久化到文件
     */
    private SessionManager(String cwd, Path sessionDir, Path sessionFile, boolean persist) {
        this.cwd = cwd;
        this.sessionDir = sessionDir;
        this.persist = persist;

        // 如果启用持久化且会话目录不为 null，确保目录存在
        if (persist && sessionDir != null) {
            try {
                Files.createDirectories(sessionDir);
            } catch (IOException e) {
                LOG.warn("创建会话目录失败: {}", sessionDir, e);
            }
        }

        if (sessionFile != null) {
            setSessionFile(sessionFile);
        } else {
            newSession(null);
        }
    }

    /**
     * 创建新的会话管理器，启用文件持久化。
     * 会话目录默认位于 ~/.pi/agent/sessions/ 下。
     *
     * @param cwd 工作目录（存储在会话头中）
     * @return 新的 SessionManager 实例
     */
    public static SessionManager create(String cwd) {
        Path sessionDir = getDefaultSessionDir(cwd);
        return new SessionManager(cwd, sessionDir, null, true);
    }

    /**
     * 创建新的会话管理器，启用文件持久化，使用自定义会话目录。
     *
     * @param cwd        工作目录（存储在会话头中）
     * @param sessionDir 自定义会话目录
     * @return 新的 SessionManager 实例
     */
    public static SessionManager create(String cwd, Path sessionDir) {
        return new SessionManager(cwd, sessionDir, null, true);
    }

    /**
     * 创建仅内存的会话管理器（不进行文件持久化）。
     * 用于测试或不需要持久化的场景。
     *
     * @param cwd 工作目录
     * @return 新的 SessionManager 实例
     */
    public static SessionManager inMemory(String cwd) {
        return new SessionManager(cwd, null, null, false);
    }

    /**
     * 打开指定的会话文件。
     * 从会话头中提取工作目录信息。
     *
     * @param path       会话文件路径
     * @param sessionDir 可选的新会话目录（用于创建新会话）
     * @return 新的 SessionManager 实例
     */
    public static SessionManager open(Path path, Path sessionDir) {
        // 从会话头中提取工作目录
        List<Object> entries = loadEntriesFromFile(path);
        String cwd = System.getProperty("user.dir");
        for (Object entry : entries) {
            if (entry instanceof SessionHeader header) {
                cwd = header.cwd() != null ? header.cwd() : cwd;
                break;
            }
        }
        Path dir = sessionDir != null ? sessionDir : path.getParent();
        return new SessionManager(cwd, dir, path, true);
    }

    // =========================================================================
    // 会话创建和加载
    // =========================================================================

    /**
     * 创建新会话，可选配置。
     *
     * <p>会生成新的会话 ID 和时间戳，创建会话头，清空所有内部状态。
     * 如果启用持久化，会在会话目录中创建新的 JSONL 文件。
     *
     * @param options 会话创建选项（可为 null，使用默认值）
     * @return 新会话文件的路径，如果为内存模式则返回 null
     */
    public Path newSession(NewSessionOptions options) {
        this.sessionId = (options != null && options.id() != null) ? options.id() : UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String parentSession = (options != null) ? options.parentSession() : null;

        SessionHeader header = SessionHeader.create(sessionId, timestamp, cwd, parentSession);

        fileEntries.clear();
        fileEntries.add(header);
        byId.clear();
        labelsById.clear();
        leafId = null;
        flushed = false;

        if (persist && sessionDir != null) {
            // 文件名格式：时间戳_会话ID.jsonl
            String fileTimestamp = timestamp.replace(":", "-").replace(".", "-");
            this.sessionFile = sessionDir.resolve(fileTimestamp + "_" + sessionId + ".jsonl");
        } else {
            this.sessionFile = null;
        }

        return sessionFile;
    }

    /**
     * 切换到不同的会话文件（用于恢复和分支操作）。
     *
     * <p>如果文件存在，加载并解析所有条目，运行版本迁移（如需要），
     * 重建内部索引。如果文件不存在或损坏，创建新会话。
     *
     * @param sessionFile 会话文件路径
     */
    public void setSessionFile(Path sessionFile) {
        this.sessionFile = sessionFile.toAbsolutePath();

        if (Files.exists(this.sessionFile)) {
            List<Object> entries = loadEntriesFromFile(this.sessionFile);

            // 如果文件为空或损坏（无有效会话头），截断并重新开始
            if (entries.isEmpty()) {
                Path explicitPath = this.sessionFile;
                newSession(null);
                this.sessionFile = explicitPath;
                rewriteFile();
                flushed = true;
                return;
            }

            fileEntries.clear();
            fileEntries.addAll(entries);

            // 从会话头中提取会话 ID
            for (Object entry : fileEntries) {
                if (entry instanceof SessionHeader header) {
                    this.sessionId = header.id() != null ? header.id() : UUID.randomUUID().toString();
                    break;
                }
            }

            // 运行版本迁移（如需要）
            if (migrateToCurrentVersion(fileEntries)) {
                rewriteFile();
            }

            buildIndex();
            flushed = true;
        } else {
            // 文件不存在，创建新会话
            Path explicitPath = this.sessionFile;
            newSession(null);
            this.sessionFile = explicitPath; // 保留指定的路径
        }
    }

    // =========================================================================
    // JSONL 文件 I/O
    // =========================================================================

    /**
     * 从 JSONL 会话文件中加载条目。
     *
     * <p>逐行读取文件，解析每行 JSON，跳过空行和格式错误的行。
     * 验证第一条有效条目是有效的会话头。
     *
     * @param filePath 会话文件路径
     * @return 解析后的条目列表（会话头 + 会话条目）
     */
    static List<Object> loadEntriesFromFile(Path filePath) {
        List<Object> entries = new ArrayList<>();

        if (!Files.exists(filePath)) {
            return entries;
        }

        try {
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    Object entry = parseEntry(line);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (Exception e) {
                    LOG.debug("跳过格式错误的行: {}", line, e);
                }
            }

            // 验证会话头
            if (entries.isEmpty()) return entries;
            Object first = entries.get(0);
            if (!(first instanceof SessionHeader header) || header.id() == null) {
                return new ArrayList<>();
            }

        } catch (IOException e) {
            LOG.warn("读取会话文件失败: {}", filePath, e);
        }

        return entries;
    }

    /**
     * 解析单行 JSON 为会话条目或会话头。
     * 根据 "type" 字段的值进行分发。
     *
     * @param json JSON 字符串
     * @return 解析后的对象（SessionHeader 或 SessionEntry）
     * @throws JsonProcessingException 如果 JSON 解析失败
     */
    private static Object parseEntry(String json) throws JsonProcessingException {
        JsonNode node = PiAiJson.MAPPER.readTree(json);
        String type = node.has("type") ? node.get("type").asText() : null;

        if ("session".equals(type)) {
            return PiAiJson.MAPPER.treeToValue(node, SessionHeader.class);
        } else {
            // 作为 SessionEntry 解析（多态）
            return PiAiJson.MAPPER.treeToValue(node, SessionEntry.class);
        }
    }

    /**
     * 将单个条目持久化到会话文件。
     *
     * <p>采用延迟写入策略：在第一条助手消息出现之前，所有条目暂存不写入；
     * 助手消息出现后，一次性写入所有条目，后续条目逐条追加。
     * 这避免了仅有用户消息而无助手回复的会话产生文件。
     *
     * @param entry 要持久化的会话条目
     */
    private void persist(SessionEntry entry) {
        if (!persist || sessionFile == null) return;

        // 检查是否已有助手消息
        boolean hasAssistant = fileEntries.stream()
                .filter(e -> e instanceof SessionMessageEntry)
                .map(e -> (SessionMessageEntry) e)
                .anyMatch(e -> "assistant".equals(e.message().role()));

        if (!hasAssistant) {
            // 标记为未刷新，等助手消息到达时一次性写入所有条目
            flushed = false;
            return;
        }

        if (!flushed) {
            // 一次性写入所有条目
            try {
                StringBuilder content = new StringBuilder();
                for (Object e : fileEntries) {
                    content.append(PiAiJson.MAPPER.writeValueAsString(e)).append("\n");
                }
                Files.writeString(sessionFile, content.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                flushed = true;
            } catch (IOException e) {
                LOG.error("写入会话文件失败: {}", sessionFile, e);
            }
        } else {
            // 追加单个条目
            try {
                String line = PiAiJson.MAPPER.writeValueAsString(entry) + "\n";
                Files.writeString(sessionFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.error("追加到会话文件失败: {}", sessionFile, e);
            }
        }
    }

    /**
     * 重写整个会话文件（用于版本迁移后）。
     * 将所有内存中的条目序列化并写入文件。
     */
    private void rewriteFile() {
        if (!persist || sessionFile == null) return;

        try {
            StringBuilder content = new StringBuilder();
            for (Object e : fileEntries) {
                content.append(PiAiJson.MAPPER.writeValueAsString(e)).append("\n");
            }
            Files.writeString(sessionFile, content.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.error("重写会话文件失败: {}", sessionFile, e);
        }
    }

    // =========================================================================
    // 内部索引管理
    // =========================================================================

    /**
     * 从文件条目构建内部索引。
     *
     * <p>建立以下索引：
     * <ul>
     *   <li>byId：条目 ID 到条目对象的映射</li>
     *   <li>labelsById：条目 ID 到标签文本的映射</li>
     *   <li>leafId：最后一条条目的 ID（初始叶子指针）</li>
     * </ul>
     */
    private void buildIndex() {
        byId.clear();
        labelsById.clear();
        leafId = null;

        for (Object entry : fileEntries) {
            if (entry instanceof SessionEntry se) {
                byId.put(se.id(), se);
                leafId = se.id();

                if (se instanceof LabelEntry label) {
                    if (label.label() != null && !label.label().isEmpty()) {
                        labelsById.put(label.targetId(), label.label());
                    } else {
                        labelsById.remove(label.targetId());
                    }
                }
            }
        }
    }

    // =========================================================================
    // 版本迁移
    // =========================================================================

    /**
     * 运行所有必要的迁移，将条目升级到当前版本。
     *
     * @param entries 要迁移的条目列表（原地修改）
     * @return 如果执行了任何迁移则返回 true
     */
    static boolean migrateToCurrentVersion(List<Object> entries) {
        SessionHeader header = null;
        for (Object e : entries) {
            if (e instanceof SessionHeader h) {
                header = h;
                break;
            }
        }

        int version = (header != null && header.version() > 0) ? header.version() : 1;

        if (version >= CURRENT_SESSION_VERSION) return false;

        if (version < 2) migrateV1ToV2(entries);
        if (version < 3) migrateV2ToV3(entries);

        return true;
    }

    /**
     * v1 → v2 迁移：为会话条目添加 id/parentId 树形结构。
     * 旧版本没有树形结构，需要为每个条目生成唯一 ID 并建立父子关系。
     */
    static void migrateV1ToV2(List<Object> entries) {
        Set<String> ids = new HashSet<>();
        String prevId = null;

        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);

            if (entry instanceof SessionHeader header) {
                // 更新会话头中的版本号
                entries.set(i, new SessionHeader(
                        header.type(),
                        2,
                        header.id(),
                        header.timestamp(),
                        header.cwd(),
                        header.parentSession()
                ));
                continue;
            }

            // 为会话条目添加 id 和 parentId
            if (entry instanceof SessionEntry se) {
                String newId = generateId(ids);
                ids.add(newId);

                SessionEntry migrated = migrateEntryWithIdAndParent(se, newId, prevId);
                entries.set(i, migrated);
                prevId = newId;
            }
        }
    }

    /**
     * v2 → v3 迁移：将 hookMessage 角色的 role 重命名为 custom。
     * 更新会话头版本号。
     */
    static void migrateV2ToV3(List<Object> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);

            if (entry instanceof SessionHeader header) {
                // 更新会话头中的版本号
                entries.set(i, new SessionHeader(
                        header.type(),
                        3,
                        header.id(),
                        header.timestamp(),
                        header.cwd(),
                        header.parentSession()
                ));
            }
            // 注意：hookMessage 角色迁移需要在 AgentMessage 级别处理
        }
    }

    /**
     * 辅助方法：重新创建会话条目，赋予新的 id 和 parentId。
     * 根据条目的实际类型调用对应的 create 方法。
     *
     * @param entry    源条目
     * @param id       新 ID
     * @param parentId 父条目 ID
     * @return 具有新 ID 和 parentId 的条目
     */
    @SuppressWarnings("unchecked")
    private static SessionEntry migrateEntryWithIdAndParent(SessionEntry entry, String id, String parentId) {
        if (entry instanceof SessionMessageEntry e) {
            return SessionMessageEntry.create(id, parentId, e.timestamp(), e.message());
        } else if (entry instanceof ThinkingLevelChangeEntry e) {
            return ThinkingLevelChangeEntry.create(id, parentId, e.timestamp(), e.thinkingLevel());
        } else if (entry instanceof ModelChangeEntry e) {
            return ModelChangeEntry.create(id, parentId, e.timestamp(), e.provider(), e.modelId());
        } else if (entry instanceof CompactionEntry<?> e) {
            return CompactionEntry.create(id, parentId, e.timestamp(), e.summary(), e.firstKeptEntryId(), e.tokensBefore(), e.details(), e.fromHook());
        } else if (entry instanceof BranchSummaryEntry<?> e) {
            return BranchSummaryEntry.create(id, parentId, e.timestamp(), e.fromId(), e.summary(), e.details(), e.fromHook());
        } else if (entry instanceof CustomEntry<?> e) {
            return CustomEntry.create(id, parentId, e.timestamp(), e.customType(), e.data());
        } else if (entry instanceof CustomMessageEntry<?> e) {
            return CustomMessageEntry.create(id, parentId, e.timestamp(), e.customType(), e.content(), e.display(), e.details());
        } else if (entry instanceof LabelEntry e) {
            return LabelEntry.create(id, parentId, e.timestamp(), e.targetId(), e.label());
        } else if (entry instanceof SessionInfoEntry e) {
            return SessionInfoEntry.create(id, parentId, e.timestamp(), e.name());
        }
        throw new IllegalArgumentException("未知条目类型: " + entry.getClass().getName());
    }

    // =========================================================================
    // ID 生成
    // =========================================================================

    /**
     * 生成唯一短 ID（8 位十六进制字符，碰撞检测）。
     * 最多尝试 100 次，如果仍有碰撞则回退到完整 UUID。
     *
     * @param existingIds 现有 ID 集合，用于碰撞检测
     * @return 唯一 ID
     */
    private static String generateId(Set<String> existingIds) {
        for (int i = 0; i < 100; i++) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            if (!existingIds.contains(id)) return id;
        }
        // 回退到完整 UUID
        return UUID.randomUUID().toString();
    }

    /**
     * 使用内部 byId 索引生成唯一 ID。
     *
     * @return 唯一 ID
     */
    private String generateId() {
        return generateId(byId.keySet());
    }

    // =========================================================================
    // 默认会话目录
    // =========================================================================

    /**
     * 计算指定工作目录的默认会话目录路径。
     *
     * <p>将工作目录编码为安全目录名，放在 ~/.pi/agent/sessions/ 下。
     * 编码规则：将路径分隔符和冒号替换为连字符，并添加 -- 前缀和后缀。
     *
     * @param cwd 工作目录
     * @return 默认会话目录路径
     */
    private static Path getDefaultSessionDir(String cwd) {
        String safePath = "--" + cwd.replaceFirst("^[/\\\\]", "").replaceAll("[/\\\\:]", "-") + "--";
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".pi", "agent", "sessions", safePath);
    }

    // =========================================================================
    // 访问器
    // =========================================================================

    /** 检查此会话管理器是否持久化到文件。 */
    public boolean isPersisted() {
        return persist;
    }

    /** 获取工作目录。 */
    public String getCwd() {
        return cwd;
    }

    /** 获取会话目录。 */
    public Path getSessionDir() {
        return sessionDir;
    }

    /** 获取会话 ID。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 获取会话文件路径。 */
    public Path getSessionFile() {
        return sessionFile;
    }

    /** 获取会话头信息。 */
    public SessionHeader getHeader() {
        for (Object entry : fileEntries) {
            if (entry instanceof SessionHeader header) {
                return header;
            }
        }
        return null;
    }

    /** 获取所有会话条目（不含会话头）。 */
    public List<SessionEntry> getEntries() {
        List<SessionEntry> entries = new ArrayList<>();
        for (Object entry : fileEntries) {
            if (entry instanceof SessionEntry se) {
                entries.add(se);
            }
        }
        return entries;
    }

    // =========================================================================
    // 树形结构遍历
    // =========================================================================

    /** 获取当前叶子条目 ID。 */
    public String getLeafId() {
        return leafId;
    }

    /** 获取当前叶子条目。 */
    public SessionEntry getLeafEntry() {
        return leafId != null ? byId.get(leafId) : null;
    }

    /** 根据 ID 获取条目。 */
    public SessionEntry getEntry(String id) {
        return byId.get(id);
    }

    /** 获取指定条目的标签，如果没有则返回 null。 */
    public String getLabel(String id) {
        return labelsById.get(id);
    }

    // =========================================================================
    // 条目追加方法
    // =========================================================================

    /**
     * 追加条目到会话。
     * 创建当前叶子的子节点，更新叶子指针，持久化到文件。
     *
     * @param entry 要追加的会话条目
     */
    private void appendEntry(SessionEntry entry) {
        fileEntries.add(entry);
        byId.put(entry.id(), entry);
        leafId = entry.id();
        persist(entry);
    }

    /**
     * 追加消息条目到会话。
     *
     * <p>验证需求：1.3
     *
     * @param message 要追加的 Agent 消息
     * @return 新条目的 ID
     */
    public String appendMessage(com.pi.agent.types.AgentMessage message) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        SessionMessageEntry entry = SessionMessageEntry.create(id, leafId, timestamp, message);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加思考级别变更条目到会话。
     *
     * <p>验证需求：1.4
     *
     * @param thinkingLevel 新的思考级别（如 "off"、"low"、"medium"、"high"）
     * @return 新条目的 ID
     */
    public String appendThinkingLevelChange(String thinkingLevel) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        ThinkingLevelChangeEntry entry = ThinkingLevelChangeEntry.create(id, leafId, timestamp, thinkingLevel);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加模型变更条目到会话。
     *
     * <p>验证需求：1.5
     *
     * @param provider 提供商标识（如 "anthropic"、"openai"）
     * @param modelId  模型标识（如 "claude-3-opus"）
     * @return 新条目的 ID
     */
    public String appendModelChange(String provider, String modelId) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        ModelChangeEntry entry = ModelChangeEntry.create(id, leafId, timestamp, provider, modelId);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加压缩条目到会话。
     *
     * <p>验证需求：1.6
     *
     * @param summary          生成的压缩摘要
     * @param firstKeptEntryId 压缩后保留的第一个条目 ID
     * @param tokensBefore     压缩前的 Token 数
     * @return 新条目的 ID
     */
    public String appendCompaction(String summary, String firstKeptEntryId, int tokensBefore) {
        return appendCompaction(summary, firstKeptEntryId, tokensBefore, null, null);
    }

    /**
     * 追加压缩条目到会话，包含可选详细信息。
     *
     * <p>验证需求：1.6
     *
     * @param summary          生成的压缩摘要
     * @param firstKeptEntryId 压缩后保留的第一个条目 ID
     * @param tokensBefore     压缩前的 Token 数
     * @param details          扩展特定的详细信息（可为 null）
     * @param fromHook         是否由扩展生成（可为 null）
     * @param <T>              详细信息类型
     * @return 新条目的 ID
     */
    public <T> String appendCompaction(String summary, String firstKeptEntryId, int tokensBefore, T details, Boolean fromHook) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        CompactionEntry<T> entry = CompactionEntry.create(id, leafId, timestamp, summary, firstKeptEntryId, tokensBefore, details, fromHook);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加分支摘要条目到会话。
     *
     * <p>验证需求：1.7
     *
     * @param fromId  分支起始条目的 ID
     * @param summary 生成的分支摘要
     * @return 新条目的 ID
     */
    public String appendBranchSummary(String fromId, String summary) {
        return appendBranchSummary(fromId, summary, null, null);
    }

    /**
     * 追加分支摘要条目到会话，包含可选详细信息。
     *
     * <p>验证需求：1.7
     *
     * @param fromId   分支起始条目的 ID
     * @param summary  生成的分支摘要
     * @param details  扩展特定的详细信息（可为 null）
     * @param fromHook 是否由扩展生成（可为 null）
     * @param <T>      详细信息类型
     * @return 新条目的 ID
     */
    public <T> String appendBranchSummary(String fromId, String summary, T details, Boolean fromHook) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        BranchSummaryEntry<T> entry = BranchSummaryEntry.create(id, leafId, timestamp, fromId, summary, details, fromHook);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加自定义条目到会话（不参与 LLM 上下文）。
     *
     * <p>自定义条目用于扩展存储特定数据，在会话重载时，
     * 扩展可以扫描其 customType 进行识别和状态恢复。
     *
     * <p>验证需求：1.8
     *
     * @param customType 扩展标识符，用于过滤条目
     * @param data       扩展特定数据（可为 null）
     * @param <T>        数据类型
     * @return 新条目的 ID
     */
    public <T> String appendCustomEntry(String customType, T data) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        CustomEntry<T> entry = CustomEntry.create(id, leafId, timestamp, customType, data);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加自定义消息条目到会话（参与 LLM 上下文）。
     *
     * <p>验证需求：1.9
     *
     * @param customType 扩展标识符
     * @param content    消息内容（String 或 List of ContentBlock）
     * @param display    是否在 TUI 中显示
     * @return 新条目的 ID
     */
    public String appendCustomMessageEntry(String customType, Object content, boolean display) {
        return appendCustomMessageEntry(customType, content, display, null);
    }

    /**
     * 追加自定义消息条目到会话，包含可选详细信息。
     *
     * <p>验证需求：1.9
     *
     * @param customType 扩展标识符
     * @param content    消息内容（String 或 List of ContentBlock）
     * @param display    是否在 TUI 中显示
     * @param details    扩展特定的元数据（可为 null，不会发送给 LLM）
     * @param <T>        详细信息类型
     * @return 新条目的 ID
     */
    public <T> String appendCustomMessageEntry(String customType, Object content, boolean display, T details) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        CustomMessageEntry<T> entry = CustomMessageEntry.create(id, leafId, timestamp, customType, content, display, details);
        appendEntry(entry);
        return id;
    }

    /**
     * 追加标签条目到会话。
     *
     * <p>标签允许用户标记对话中的特定位置，便于导航。
     * 传入 null 或空字符串会清除标签。
     *
     * <p>验证需求：1.10
     *
     * @param targetId 要标记的条目 ID
     * @param label    标签文本（null 或空字符串表示清除）
     * @return 新条目的 ID
     */
    public String appendLabelChange(String targetId, String label) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        LabelEntry entry = LabelEntry.create(id, leafId, timestamp, targetId, label);
        appendEntry(entry);

        // 更新标签索引
        if (label != null && !label.isEmpty()) {
            labelsById.put(targetId, label);
        } else {
            labelsById.remove(targetId);
        }

        return id;
    }

    /**
     * 追加会话信息条目到会话。
     *
     * <p>验证需求：1.11
     *
     * @param name 用户定义的会话显示名称
     * @return 新条目的 ID
     */
    public String appendSessionInfo(String name) {
        String id = generateId();
        String timestamp = Instant.now().toString();
        SessionInfoEntry entry = SessionInfoEntry.create(id, leafId, timestamp, name);
        appendEntry(entry);
        return id;
    }

    // =========================================================================
    // 树形结构遍历方法
    // =========================================================================

    /**
     * 设置当前叶子指针（用于分支操作）。
     *
     * <p>将叶子指针移动到指定条目，后续的 append 操作会创建该条目的子节点。
     * 传入 null 可重置叶子指针。
     *
     * <p>验证需求：1.14
     *
     * @param entryId 要设置为叶子的条目 ID（null 表示重置）
     * @throws IllegalArgumentException 如果条目 ID 不存在
     */
    public void setLeaf(String entryId) {
        if (entryId != null && !byId.containsKey(entryId)) {
            throw new IllegalArgumentException("条目 " + entryId + " 未找到");
        }
        this.leafId = entryId;
    }

    /**
     * 获取指定条目的所有子条目。
     *
     * <p>验证需求：1.15
     *
     * @param parentId 父条目 ID（null 表示根级别条目）
     * @return 子条目列表
     */
    public List<SessionEntry> getChildren(String parentId) {
        List<SessionEntry> children = new ArrayList<>();
        for (Object entry : fileEntries) {
            if (entry instanceof SessionEntry se) {
                if (Objects.equals(se.parentId(), parentId)) {
                    children.add(se);
                }
            }
        }
        return children;
    }

    /**
     * 获取从指定条目到根节点的分支路径。
     *
     * <p>通过 parentId 链向上回溯，直到根节点（parentId 为 null）。
     *
     * <p>验证需求：1.16
     *
     * @param fromId 起始条目 ID
     * @return 从根节点到指定条目的路径列表（包含两端）
     */
    public List<SessionEntry> getBranch(String fromId) {
        List<SessionEntry> path = new ArrayList<>();
        SessionEntry current = byId.get(fromId);

        while (current != null) {
            path.add(0, current);
            current = current.parentId() != null ? byId.get(current.parentId()) : null;
        }

        return path;
    }

    // =========================================================================
    // 会话上下文构建
    // =========================================================================

    /**
     * 构建发送给 LLM 的会话上下文。
     *
     * <p>从当前叶子节点回溯到根节点，收集消息，处理：
     * <ul>
     *   <li>压缩条目：先输出摘要，然后输出保留的消息</li>
     *   <li>分支摘要条目：将摘要作为用户消息输出</li>
     *   <li>自定义消息条目：将内容作为用户消息输出（如果 display=true）</li>
     *   <li>模型变更和思考级别变更：提取最新的值</li>
     * </ul>
     *
     * <p>验证需求：1.12, 1.13
     *
     * @return 包含消息、思考级别和模型的会话上下文
     */
    public SessionContext buildSessionContext() {
        return buildSessionContext(getEntries(), leafId, byId);
    }

    /**
     * 从条目列表构建会话上下文，使用树形遍历。
     *
     * <p>验证需求：1.12, 1.13
     *
     * @param entries 会话条目列表
     * @param leafId  叶子条目 ID（null 表示空上下文）
     * @param byId    条目 ID 到条目的映射
     * @return 会话上下文
     */
    static SessionContext buildSessionContext(
            List<SessionEntry> entries,
            String leafId,
            Map<String, SessionEntry> byId
    ) {
        // 如果未提供索引，构建临时索引
        if (byId == null) {
            byId = new HashMap<>();
            for (SessionEntry entry : entries) {
                byId.put(entry.id(), entry);
            }
        }

        // 显式返回空——无消息
        if (leafId == null && entries.isEmpty()) {
            return SessionContext.empty();
        }

        // 查找叶子节点
        SessionEntry leaf = null;
        if (leafId != null) {
            leaf = byId.get(leafId);
        }
        if (leaf == null && !entries.isEmpty()) {
            // 回退到最后一条条目
            leaf = entries.get(entries.size() - 1);
        }

        if (leaf == null) {
            return SessionContext.empty();
        }

        // 从叶子回溯到根，收集路径
        List<SessionEntry> path = new ArrayList<>();
        SessionEntry current = leaf;
        while (current != null) {
            path.add(0, current);
            current = current.parentId() != null ? byId.get(current.parentId()) : null;
        }

        // 提取设置和最新的压缩信息
        String thinkingLevel = "off";
        String provider = null;
        String modelId = null;
        CompactionEntry<?> compaction = null;
        int compactionIndex = -1;

        for (int i = 0; i < path.size(); i++) {
            SessionEntry entry = path.get(i);
            if (entry instanceof ThinkingLevelChangeEntry tlc) {
                thinkingLevel = tlc.thinkingLevel();
            } else if (entry instanceof ModelChangeEntry mc) {
                provider = mc.provider();
                modelId = mc.modelId();
            } else if (entry instanceof CompactionEntry<?> ce) {
                compaction = ce;
                compactionIndex = i;
            }
        }

        // 构建消息列表
        List<com.pi.agent.types.AgentMessage> messages = new ArrayList<>();

        // 根据压缩信息确定起始索引
        int startIndex = 0;
        if (compaction != null && compaction.firstKeptEntryId() != null) {
            // 查找第一个保留条目的索引
            for (int i = compactionIndex + 1; i < path.size(); i++) {
                if (path.get(i).id().equals(compaction.firstKeptEntryId())) {
                    startIndex = i;
                    break;
                }
            }

            // 先输出压缩摘要作为用户消息
            messages.add(createSummaryMessage(compaction.summary(), compaction.timestamp()));
        }

        // 从路径中收集消息
        for (int i = startIndex; i < path.size(); i++) {
            SessionEntry entry = path.get(i);

            if (entry instanceof SessionMessageEntry sme) {
                messages.add(sme.message());
            } else if (entry instanceof BranchSummaryEntry<?> bse) {
                // 将分支摘要作为用户消息输出
                messages.add(createSummaryMessage(bse.summary(), bse.timestamp()));
            } else if (entry instanceof CustomMessageEntry<?> cme) {
                // 自定义消息条目参与 LLM 上下文
                messages.add(createCustomMessage(cme));
            }
            // 跳过其他条目类型（ThinkingLevelChange、ModelChange、Custom、Label、SessionInfo、Compaction）
        }

        return SessionContext.of(messages, thinkingLevel, provider, modelId);
    }

    /**
     * 创建摘要消息（用于压缩和分支摘要）。
     *
     * @param summary   摘要文本
     * @param timestamp ISO 8601 时间戳
     * @return 摘要消息
     */
    private static com.pi.agent.types.AgentMessage createSummaryMessage(String summary, String timestamp) {
        long ts = parseTimestamp(timestamp);
        return new SummaryMessage(summary, ts);
    }

    /**
     * 从 CustomMessageEntry 创建自定义消息。
     *
     * @param entry 自定义消息条目
     * @return 自定义消息
     */
    private static com.pi.agent.types.AgentMessage createCustomMessage(CustomMessageEntry<?> entry) {
        long ts = parseTimestamp(entry.timestamp());
        Object content = entry.content();
        String text = content instanceof String ? (String) content : content.toString();
        return new CustomAgentMessage(entry.customType(), text, ts);
    }

    /**
     * 解析 ISO 8601 时间戳为 Unix 毫秒数。
     *
     * @param timestamp ISO 8601 时间戳字符串
     * @return Unix 毫秒数，解析失败时返回当前时间
     */
    private static long parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 内部消息类型：用于压缩和分支摘要。
     * 角色固定为 "user"。
     */
    private record SummaryMessage(String summary, long timestamp) implements com.pi.agent.types.AgentMessage {
        @Override
        public String role() {
            return "user";
        }

        @Override
        public String toString() {
            return summary;
        }
    }

    /**
     * 内部消息类型：用于自定义消息。
     * 角色固定为 "custom"。
     */
    private record CustomAgentMessage(String customType, String content, long timestamp) implements com.pi.agent.types.AgentMessage {
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