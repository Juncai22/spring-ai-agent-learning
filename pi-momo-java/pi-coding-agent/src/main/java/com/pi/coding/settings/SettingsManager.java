package com.pi.coding.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pi.ai.core.util.PiAiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * pi-coding-agent 的配置管理器。
 *
 * <p>从两个配置源加载配置并进行深度合并：
 * <ol>
 *   <li>全局配置：{@code ~/.pi/settings.json} — 用户级别的全局默认配置</li>
 *   <li>项目配置：{@code {cwd}/.pi/settings.json} — 当前工作目录下的项目级配置</li>
 * </ol>
 *
 * <p>当配置项冲突时，项目配置优先于全局配置（即项目配置覆盖全局配置中的同名项）。
 * 对于嵌套对象（如 compaction、retry 等），采用深度合并策略，项目级字段只覆盖全局级对应的字段，
 * 而非整体替换。
 *
 * <p>写入操作使用文件锁（FileLock）防止并发写入导致的数据损坏。读取操作则直接读取文件，
 * 不进行缓存，保证每次读取都是最新的配置内容。
 */
public class SettingsManager {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(SettingsManager.class);
    /** Jackson ObjectMapper 实例，用于 JSON 序列化与反序列化 */
    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    /** 全局配置文件的路径（用户主目录下的 .pi/settings.json） */
    private final Path globalSettingsPath;
    /** 项目配置文件的路径（当前工作目录下的 .pi/settings.json） */
    private final Path projectSettingsPath;

    /** 合并后的有效配置（项目配置覆盖全局配置后的结果），volatile 保证多线程可见性 */
    private volatile SettingsData effectiveSettings;

    /**
     * 私有构造方法，加载并合并全局和项目配置。
     *
     * @param globalSettingsPath 全局配置文件路径（可为 null）
     * @param projectSettingsPath 项目配置文件路径（可为 null）
     */
    private SettingsManager(Path globalSettingsPath, Path projectSettingsPath) {
        this.globalSettingsPath = globalSettingsPath;
        this.projectSettingsPath = projectSettingsPath;
        this.effectiveSettings = loadAndMerge();
    }

    /**
     * 为指定的工作目录和代理配置目录创建 SettingsManager 实例。
     *
     * @param cwd      当前工作目录，不能为 null
     * @param agentDir 代理配置目录名称（例如 ".pi"），不能为 null
     * @return 新的 SettingsManager 实例
     */
    public static SettingsManager create(String cwd, String agentDir) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        Objects.requireNonNull(agentDir, "agentDir must not be null");
        Path home = Path.of(System.getProperty("user.home"));
        Path globalPath = home.resolve(agentDir).resolve("settings.json");
        Path projectPath = Path.of(cwd).resolve(agentDir).resolve("settings.json");
        return new SettingsManager(globalPath, projectPath);
    }

    /**
     * 创建一个仅内存的 SettingsManager，使用默认配置，不读写磁盘文件。
     * 主要用于测试场景，避免对文件系统的依赖。
     *
     * @return 新的仅内存 SettingsManager 实例
     */
    public static SettingsManager inMemory() {
        return new SettingsManager(null, null) {
            /** 内存中的配置数据，初始为空的默认配置 */
            private SettingsData data = SettingsData.EMPTY;

            @Override
            public void save(SettingsUpdate update) {
                data = applyUpdate(data, update);
            }

            @Override
            protected SettingsData loadAndMerge() {
                return SettingsData.EMPTY;
            }
        };
    }

    // ========== 基础配置（Basic Settings） ==========

    /**
     * 获取默认的 AI 提供商名称。
     *
     * @return 默认提供商，如果未配置则返回 "anthropic"
     */
    public String getDefaultProvider() {
        return effectiveSettings.defaultProvider() != null
            ? effectiveSettings.defaultProvider() : "anthropic";
    }

    /**
     * 获取默认的 AI 模型名称。
     *
     * @return 默认模型，如果未配置则返回 "claude-sonnet-4-20250514"
     */
    public String getDefaultModel() {
        return effectiveSettings.defaultModel() != null
            ? effectiveSettings.defaultModel() : "claude-sonnet-4-20250514";
    }

    /**
     * 获取默认的思考级别（thinking level）。
     *
     * @return 默认思考级别，如果未配置则返回 "none"（不启用思考）
     */
    public String getDefaultThinkingLevel() {
        return effectiveSettings.defaultThinkingLevel() != null
            ? effectiveSettings.defaultThinkingLevel() : "none";
    }

    /**
     * 获取通信传输方式（transport）。
     *
     * @return 传输方式，如果未配置则返回 "http"（HTTP 协议）
     */
    public String getTransport() {
        return effectiveSettings.transport() != null
            ? effectiveSettings.transport() : "http";
    }

    /**
     * 获取导向模式（steering mode），控制 AI 回复的引导方式。
     *
     * @return 导向模式，如果未配置则返回 "auto"（自动模式）
     */
    public String getSteeringMode() {
        return effectiveSettings.steeringMode() != null
            ? effectiveSettings.steeringMode() : "auto";
    }

    /**
     * 获取跟进模式（follow-up mode），控制 AI 是否主动发起跟进对话。
     *
     * @return 跟进模式，如果未配置则返回 "auto"（自动模式）
     */
    public String getFollowUpMode() {
        return effectiveSettings.followUpMode() != null
            ? effectiveSettings.followUpMode() : "auto";
    }

    /**
     * 获取当前主题名称。
     *
     * @return 主题名称，如果未配置则返回 "default"（默认主题）
     */
    public String getTheme() {
        return effectiveSettings.theme() != null
            ? effectiveSettings.theme() : "default";
    }

    // ========== 功能配置（Feature Settings） ==========

    /**
     * 获取上下文压缩（compaction）设置，用于控制对话历史压缩策略。
     *
     * @return CompactionSettings 实例，如果未配置则返回默认值
     */
    public CompactionSettings getCompactionSettings() {
        return effectiveSettings.compaction() != null
            ? effectiveSettings.compaction() : CompactionSettings.DEFAULT;
    }

    /**
     * 获取分支摘要（branch summary）设置，用于控制 Git 分支切换时的摘要生成行为。
     *
     * @return BranchSummarySettings 实例，如果未配置则返回默认值
     */
    public BranchSummarySettings getBranchSummarySettings() {
        return effectiveSettings.branchSummary() != null
            ? effectiveSettings.branchSummary() : BranchSummarySettings.DEFAULT;
    }

    /**
     * 获取自动重试（retry）设置，用于控制 API 调用失败时的重试策略。
     *
     * @return RetrySettings 实例，如果未配置则返回默认值
     */
    public RetrySettings getRetrySettings() {
        return effectiveSettings.retry() != null
            ? effectiveSettings.retry() : RetrySettings.DEFAULT;
    }

    // ========== 终端设置（Terminal Settings） ==========

    /**
     * 获取是否在终端中显示图片。
     *
     * @return 如果未配置则默认返回 true（显示图片）
     */
    public boolean getShowImages() {
        return effectiveSettings.showImages() != null
            ? effectiveSettings.showImages() : true;
    }

    /**
     * 获取终端缩小窗口时是否清除内容。
     *
     * @return 如果未配置则默认返回 false（不清除）
     */
    public boolean getClearOnShrink() {
        return effectiveSettings.clearOnShrink() != null
            ? effectiveSettings.clearOnShrink() : false;
    }

    // ========== 图片设置（Image Settings） ==========

    /**
     * 获取是否自动调整图片尺寸。
     *
     * @return 如果未配置则默认返回 true（自动调整）
     */
    public boolean getAutoResize() {
        return effectiveSettings.autoResize() != null
            ? effectiveSettings.autoResize() : true;
    }

    /**
     * 获取是否阻止所有图片的加载和显示。
     *
     * @return 如果未配置则默认返回 false（不阻止图片）
     */
    public boolean getBlockImages() {
        return effectiveSettings.blockImages() != null
            ? effectiveSettings.blockImages() : false;
    }

    // ========== 思考预算（Thinking Budgets） ==========

    /**
     * 获取思考预算设置，控制不同思考级别（low/medium/high/xhigh）的 Token 预算。
     *
     * @return ThinkingBudgets 实例，如果未配置则返回默认值
     */
    public ThinkingBudgets getThinkingBudgets() {
        return effectiveSettings.thinkingBudgets() != null
            ? effectiveSettings.thinkingBudgets() : ThinkingBudgets.DEFAULT;
    }

    // ========== 路径设置（Path Settings） ==========

    /**
     * 获取扩展（extension）的搜索路径列表。
     *
     * @return 扩展路径列表，如果未配置则返回空列表
     */
    public List<String> getExtensionPaths() {
        return effectiveSettings.extensionPaths() != null
            ? effectiveSettings.extensionPaths() : Collections.emptyList();
    }

    /**
     * 获取技能（skill）的搜索路径列表。
     *
     * @return 技能路径列表，如果未配置则返回空列表
     */
    public List<String> getSkillPaths() {
        return effectiveSettings.skillPaths() != null
            ? effectiveSettings.skillPaths() : Collections.emptyList();
    }

    /**
     * 获取提示词（prompt）模板的搜索路径列表。
     *
     * @return 提示词路径列表，如果未配置则返回空列表
     */
    public List<String> getPromptPaths() {
        return effectiveSettings.promptPaths() != null
            ? effectiveSettings.promptPaths() : Collections.emptyList();
    }

    /**
     * 获取主题（theme）文件的搜索路径列表。
     *
     * @return 主题路径列表，如果未配置则返回空列表
     */
    public List<String> getThemePaths() {
        return effectiveSettings.themePaths() != null
            ? effectiveSettings.themePaths() : Collections.emptyList();
    }

    // ========== 保存操作（Save） ==========

    /**
     * 将部分配置更新保存到项目配置文件中。
     * 只有 update 中非 null 的字段会被持久化，null 字段表示保留现有值不变。
     *
     * <p>保存过程使用文件锁保证线程安全，流程如下：
     * <ol>
     *   <li>确保父目录存在</li>
     *   <li>获取文件锁，防止并发写入</li>
     *   <li>读取现有项目配置</li>
     *   <li>应用更新（仅覆盖非 null 字段）</li>
     *   <li>将更新后的配置写回文件</li>
     *   <li>重新加载合并后的全局+项目配置</li>
     * </ol>
     *
     * @param update 要应用的部分配置更新
     */
    public void save(SettingsUpdate update) {
        if (projectSettingsPath == null) return;

        try {
            Path parent = projectSettingsPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            withFileLock(projectSettingsPath, () -> {
                // 加载现有的项目配置
                SettingsData existing = loadFromFile(projectSettingsPath);
                // 应用更新（仅覆盖非 null 字段）
                SettingsData updated = applyUpdate(existing, update);
                // 将更新后的配置写回文件
                String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(updated);
                Files.writeString(projectSettingsPath, json);
                return null;
            });

            // 重新加载合并后的全局 + 项目配置
            this.effectiveSettings = loadAndMerge();
        } catch (IOException e) {
            log.error("保存配置到 {} 失败", projectSettingsPath, e);
        }
    }

    /**
     * 从磁盘重新加载配置文件，并刷新合并后的有效配置。
     * 通常在外部修改了配置文件后调用，以同步最新配置。
     */
    public void reload() {
        this.effectiveSettings = loadAndMerge();
    }

    // ========== 私有辅助方法（Private Helpers） ==========

    /**
     * 从磁盘加载全局配置和项目配置，并进行深度合并。
     * 项目配置中的字段会覆盖全局配置中的对应字段。
     *
     * @return 合并后的 SettingsData 实例
     */
    protected SettingsData loadAndMerge() {
        SettingsData global = globalSettingsPath != null
            ? loadFromFile(globalSettingsPath) : SettingsData.EMPTY;
        SettingsData project = projectSettingsPath != null
            ? loadFromFile(projectSettingsPath) : SettingsData.EMPTY;
        return deepMerge(global, project);
    }

    /**
     * 从指定路径加载配置文件，解析为 SettingsData 对象。
     * 如果文件不存在、内容为空或解析失败，则返回空配置。
     *
     * @param path 配置文件路径
     * @return 解析后的 SettingsData 实例，失败时返回 SettingsData.EMPTY
     */
    private SettingsData loadFromFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return SettingsData.EMPTY;
        }
        try {
            String content = Files.readString(path);
            if (content.isBlank()) return SettingsData.EMPTY;
            SettingsData loaded = MAPPER.readValue(content, SettingsData.class);
            return loaded != null ? migrateIfNeeded(loaded) : SettingsData.EMPTY;
        } catch (IOException e) {
            log.warn("从 {} 加载配置失败: {}", path, e.getMessage());
            return SettingsData.EMPTY;
        }
    }

    /**
     * 深度合并两个 SettingsData 对象。
     * 项目配置覆盖全局配置，对于嵌套对象（如 compaction、retry 等），
     * 会递归合并内部字段，而非整体替换。
     *
     * <p>合并策略：
     * <ul>
     *   <li>简单字段（字符串、布尔值、整数）：项目值覆盖全局值</li>
     *   <li>嵌套对象字段：递归合并，项目中的字段值覆盖全局中对应字段值</li>
     *   <li>null 值：项目中的 null 值不会覆盖全局中的非 null 值</li>
     * </ul>
     *
     * @param global  全局配置（基础）
     * @param project 项目配置（覆盖层）
     * @return 合并后的 SettingsData 实例
     */
    static SettingsData deepMerge(SettingsData global, SettingsData project) {
        try {
            JsonNode globalNode = MAPPER.valueToTree(global);
            JsonNode projectNode = MAPPER.valueToTree(project);
            JsonNode merged = mergeNodes(globalNode, projectNode);
            return MAPPER.treeToValue(merged, SettingsData.class);
        } catch (Exception e) {
            log.warn("深度合并配置失败，将使用项目配置", e);
            return project;
        }
    }

    /**
     * 递归合并两个 JSON 节点树。
     * 对于对象类型的节点，会递归合并其子字段；对于非对象类型的节点，直接使用覆盖值。
     *
     * @param base     基础节点（全局配置）
     * @param override 覆盖节点（项目配置）
     * @return 合并后的 JsonNode
     */
    private static JsonNode mergeNodes(JsonNode base, JsonNode override) {
        if (!base.isObject() || !override.isObject()) {
            return override;
        }
        ObjectNode result = (ObjectNode) base.deepCopy();
        override.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode overrideValue = entry.getValue();
            if (!overrideValue.isNull()) {
                JsonNode baseValue = result.get(key);
                if (baseValue != null && baseValue.isObject() && overrideValue.isObject()) {
                    result.set(key, mergeNodes(baseValue, overrideValue));
                } else {
                    result.set(key, overrideValue);
                }
            }
        });
        return result;
    }

    /**
     * 将部分更新（SettingsUpdate）应用到现有的配置数据（SettingsData）上。
     * 只有 update 中非 null 的字段会覆盖 existing 中的对应字段，null 字段保留原值。
     *
     * @param existing 现有的配置数据
     * @param update   部分更新的配置数据
     * @return 应用更新后的新 SettingsData 实例
     */
    static SettingsData applyUpdate(SettingsData existing, SettingsUpdate update) {
        return new SettingsData(
            update.defaultProvider() != null ? update.defaultProvider() : existing.defaultProvider(),
            update.defaultModel() != null ? update.defaultModel() : existing.defaultModel(),
            update.defaultThinkingLevel() != null ? update.defaultThinkingLevel() : existing.defaultThinkingLevel(),
            update.transport() != null ? update.transport() : existing.transport(),
            update.steeringMode() != null ? update.steeringMode() : existing.steeringMode(),
            update.followUpMode() != null ? update.followUpMode() : existing.followUpMode(),
            update.theme() != null ? update.theme() : existing.theme(),
            update.showImages() != null ? update.showImages() : existing.showImages(),
            update.clearOnShrink() != null ? update.clearOnShrink() : existing.clearOnShrink(),
            update.autoResize() != null ? update.autoResize() : existing.autoResize(),
            update.blockImages() != null ? update.blockImages() : existing.blockImages(),
            update.compaction() != null ? update.compaction() : existing.compaction(),
            update.branchSummary() != null ? update.branchSummary() : existing.branchSummary(),
            update.retry() != null ? update.retry() : existing.retry(),
            update.thinkingBudgets() != null ? update.thinkingBudgets() : existing.thinkingBudgets(),
            update.extensionPaths() != null ? update.extensionPaths() : existing.extensionPaths(),
            update.skillPaths() != null ? update.skillPaths() : existing.skillPaths(),
            update.promptPaths() != null ? update.promptPaths() : existing.promptPaths(),
            update.themePaths() != null ? update.themePaths() : existing.themePaths()
        );
    }

    /**
     * 在需要时对配置数据进行迁移，从旧格式升级到新格式。
     * 当前尚无迁移需求，此方法作为占位符，为未来配置格式变更预留扩展点。
     *
     * @param data 加载的原始配置数据
     * @return 迁移后的配置数据（当前直接返回原始数据）
     */
    private SettingsData migrateIfNeeded(SettingsData data) {
        // 当前不需要任何迁移操作 - 为未来迁移预留的占位方法
        return data;
    }

    /**
     * 在文件锁保护下执行指定操作，保证并发安全。
     * 如果文件不存在，会先创建空文件（写入 "{}"）再获取锁。
     *
     * <p>使用 RandomAccessFile 的 FileChannel.lock() 获取独占锁，
     * 确保同一时间只有一个线程/进程能写入该配置文件。
     *
     * @param path      要锁定的文件路径
     * @param operation 在锁保护下执行的操作
     * @return 操作返回的结果
     * @throws IOException 如果文件操作失败
     */
    private <T> T withFileLock(Path path, IOSupplier<T> operation) throws IOException {
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, "{}");
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {
            return operation.get();
        }
    }

    /**
     * 可抛出 IOException 的 Supplier 函数式接口。
     * 用于在文件锁操作中包装可能抛出 IO 异常的 Lambda 表达式。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    private interface IOSupplier<T> {
        /**
         * 获取结果，可能抛出 IOException。
         *
         * @return 结果值
         * @throws IOException 如果 IO 操作失败
         */
        T get() throws IOException;
    }
}
