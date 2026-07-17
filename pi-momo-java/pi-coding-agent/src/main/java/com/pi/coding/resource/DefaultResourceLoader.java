package com.pi.coding.resource;

import com.pi.coding.extension.LoadExtensionsResult;
import com.pi.coding.settings.SettingsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ResourceLoader 接口的默认实现，负责从多种来源加载 Agent 资源。
 *
 * <p>资源加载来源（按优先级从低到高）：
 * <ul>
 *   <li><b>全局 Agent 目录</b> — 即 agentDir/，包含用户级别的资源配置</li>
 *   <li><b>项目本地目录</b> — 即 cwd/.kiro/，包含项目级别的资源配置</li>
 *   <li><b>配置中显式指定的路径</b> — 通过 SettingsManager 获取的额外路径</li>
 *   <li><b>扩展模块提供的路径</b> — 通过 {@link #extendResources(ResourceExtensionPaths)} 注入</li>
 * </ul>
 *
 * <p><b>资源加载流程：</b>
 * <ol>
 *   <li>调用 {@link #reload()} 方法，按顺序加载 Skills、Prompts、上下文文件、系统提示</li>
 *   <li>加载过程中收集诊断信息（警告、冲突等）</li>
 *   <li>加载完成后通知所有注册的 {@link ResourceChangeListener}</li>
 *   <li>如果加载失败，自动恢复至上一状态，保证资源状态一致性</li>
 * </ol>
 *
 * <p><b>热重载支持：</b>
 * 通过 {@link SkillsWatcher} 监控 Skills 目录的文件变化。当检测到变化时，
 * 使用防抖机制（默认 500ms）合并短时间内的多次变化，然后自动触发重载。
 *
 * <p><b>线程安全：</b>
 * 缓存结果使用 volatile 关键字保证可见性，监听器列表使用 CopyOnWriteArrayList
 * 保证并发安全。重载操作在独立线程中异步执行。
 */
public class DefaultResourceLoader implements ResourceLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(DefaultResourceLoader.class);

    /** 上下文文件名列表，按优先级排列（AGENTS.md 优先于 CLAUDE.md）。 */
    private static final String[] CONTEXT_FILE_NAMES = {"AGENTS.md", "CLAUDE.md"};

    /** 系统提示文件名。 */
    private static final String SYSTEM_PROMPT_FILE = "SYSTEM.md";

    /** 附加系统提示文件名。 */
    private static final String APPEND_SYSTEM_PROMPT_FILE = "APPEND_SYSTEM.md";

    /** 当前工作目录，用于查找项目级资源。 */
    private final String cwd;

    /** Agent 全局配置目录，用于查找用户级资源。 */
    private final String agentDir;

    /** 配置管理器，用于获取显式配置的路径。 */
    private final SettingsManager settingsManager;

    // ==================== 热重载支持 ====================

    /** 注册的资源变化监听器列表（线程安全）。 */
    private final List<ResourceChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    /** Skills 文件监听器，监控 Skills 目录变化。 */
    private volatile SkillsWatcher skillsWatcher;

    // ==================== 扩展贡献的额外路径 ====================

    /** 扩展模块贡献的额外扩展路径。 */
    private List<String> additionalExtensionPaths = new ArrayList<>();

    /** 扩展模块贡献的额外 Skills 路径。 */
    private List<String> additionalSkillPaths = new ArrayList<>();

    /** 扩展模块贡献的额外 Prompt 路径。 */
    private List<String> additionalPromptPaths = new ArrayList<>();

    // ==================== 缓存结果 ====================

    /** 缓存扩展加载结果。 */
    private volatile LoadExtensionsResult extensionsResult;

    /** 缓存 Skills 加载结果。 */
    private volatile LoadSkillsResult skillsResult;

    /** 缓存 Prompt 模板加载结果。 */
    private volatile LoadPromptsResult promptsResult;

    /** 缓存上下文文件列表。 */
    private volatile List<ContextFile> agentsFiles;

    /** 缓存系统提示内容。 */
    private volatile String systemPrompt;

    /** 缓存附加系统提示行列表。 */
    private volatile List<String> appendSystemPrompt;

    /** 缓存诊断信息列表。 */
    private volatile List<ResourceDiagnostic> diagnostics;
    
    /**
     * 创建 DefaultResourceLoader 实例。
     *
     * <p>构造过程：
     * <ol>
     *   <li>使用空结果初始化所有缓存</li>
     *   <li>初始化 SkillsWatcher（但不启动监控）</li>
     *   <li>执行首次资源加载（同步等待完成）</li>
     * </ol>
     *
     * <p>如果首次加载失败，会记录警告日志并以空资源继续运行。
     *
     * @param config 资源配置，包含工作目录、Agent 目录和配置管理器
     * @throws NullPointerException 如果 config 中的任何必需参数为 null
     */
    public DefaultResourceLoader(ResourceLoaderConfig config) {
        this.cwd = config.cwd();
        this.agentDir = config.agentDir();
        this.settingsManager = config.settingsManager();

        // 使用空结果初始化所有缓存
        this.extensionsResult = new LoadExtensionsResult(List.of(), List.of());
        this.skillsResult = new LoadSkillsResult(List.of(), List.of());
        this.promptsResult = new LoadPromptsResult(List.of(), List.of());
        this.agentsFiles = List.of();
        this.systemPrompt = null;
        this.appendSystemPrompt = List.of();
        this.diagnostics = List.of();

        // 初始化监听器（但不启动文件监控）
        initializeWatcher();

        // 执行首次加载，确保资源立即可用
        try {
            reload().join();
        } catch (Exception e) {
            logger.warn("首次资源加载失败，将以空资源继续运行: {}", e.getMessage());
        }
    }
    
    @Override
    public CompletableFuture<Void> reload() {
        return CompletableFuture.runAsync(() -> {
            List<ResourceDiagnostic> allDiagnostics = new ArrayList<>();

            // 保存上一状态，用于错误恢复
            LoadSkillsResult previousSkills = this.skillsResult;
            LoadPromptsResult previousPrompts = this.promptsResult;

            try {
                // 1. 加载 Skills（技能）
                loadSkillsInternal(allDiagnostics);

                // 2. 加载 Prompt 模板
                loadPromptsInternal(allDiagnostics);

                // 3. 加载上下文文件（AGENTS.md / CLAUDE.md）
                loadContextFiles();

                // 4. 加载系统提示（SYSTEM.md）
                loadSystemPromptInternal();

                // 5. 加载附加系统提示（APPEND_SYSTEM.md）
                loadAppendSystemPromptInternal();

                // 更新诊断信息（不可修改的快照）
                this.diagnostics = List.copyOf(allDiagnostics);

                // 通知所有监听器资源已变化
                notifyListeners();

            } catch (Exception e) {
                logger.error("重载过程中发生错误，正在恢复上一状态: {}", e.getMessage());
                // 错误恢复：还原到上一状态，保证资源状态一致性
                this.skillsResult = previousSkills;
                this.promptsResult = previousPrompts;
                throw e;
            }
        });
    }
    
    @Override
    public LoadExtensionsResult getExtensions() {
        return extensionsResult;
    }

    /**
     * 设置扩展加载结果。
     *
     * <p>此方法由 {@link com.pi.coding.extension.ExtensionLoader} 在外部加载扩展后调用。
     * 扩展加载完成后，会通过 {@link #extendResources(ResourceExtensionPaths)} 将
     * 扩展贡献的路径注入到资源加载器中。
     *
     * @param result 扩展加载结果，包含扩展列表和诊断信息
     */
    public void setExtensions(LoadExtensionsResult result) {
        this.extensionsResult = result;
    }
    
    @Override
    public LoadSkillsResult getSkills() {
        return skillsResult;
    }
    
    @Override
    public LoadPromptsResult getPrompts() {
        return promptsResult;
    }
    
    @Override
    public List<ContextFile> getAgentsFiles() {
        return agentsFiles;
    }
    
    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }
    
    @Override
    public List<String> getAppendSystemPrompt() {
        return appendSystemPrompt;
    }
    
    @Override
    public List<ResourceDiagnostic> getDiagnostics() {
        return diagnostics;
    }
    
    @Override
    public void extendResources(ResourceExtensionPaths paths) {
        // 将扩展贡献的路径合并到现有路径中（自动去重）
        this.additionalExtensionPaths = mergePaths(
            this.additionalExtensionPaths, paths.extensionPaths()
        );
        this.additionalSkillPaths = mergePaths(
            this.additionalSkillPaths, paths.skillPaths()
        );
        this.additionalPromptPaths = mergePaths(
            this.additionalPromptPaths, paths.promptPaths()
        );
    }
    
    // ==================== 内部加载方法 ====================

    /**
     * 内部加载 Skills 的方法。
     *
     * <p>从配置路径和扩展贡献路径中加载 Skills。
     * 加载结果会缓存在 {@link #skillsResult} 中，并在失败时清空为空的加载结果。
     *
     * @param allDiagnostics 诊断信息收集列表
     */
    private void loadSkillsInternal(List<ResourceDiagnostic> allDiagnostics) {
        try {
            List<String> skillPaths = new ArrayList<>(settingsManager.getSkillPaths());
            skillPaths.addAll(additionalSkillPaths);

            LoadSkillsResult result = Skills.loadSkills(new LoadSkillsOptions(
                cwd, agentDir, skillPaths, true
            ));

            this.skillsResult = result;
            allDiagnostics.addAll(result.diagnostics());
        } catch (Exception e) {
            logger.warn("加载 Skills 时出错: {}", e.getMessage());
            this.skillsResult = new LoadSkillsResult(List.of(), List.of());
        }
    }

    /**
     * 内部加载 Prompt 模板的方法。
     *
     * <p>从配置路径和扩展贡献路径中加载 Prompt 模板。
     * 加载后会进行去重处理，同名的模板会生成冲突诊断。
     * 加载结果会缓存在 {@link #promptsResult} 中。
     *
     * @param allDiagnostics 诊断信息收集列表
     */
    private void loadPromptsInternal(List<ResourceDiagnostic> allDiagnostics) {
        try {
            List<String> promptPaths = new ArrayList<>(settingsManager.getPromptPaths());
            promptPaths.addAll(additionalPromptPaths);

            List<PromptTemplate> templates = PromptTemplates.loadPromptTemplates(
                new LoadPromptTemplatesOptions(cwd, agentDir, promptPaths, true)
            );

            // 对 Prompt 模板进行去重
            DedupeResult<PromptTemplate> deduped = dedupePrompts(templates);

            this.promptsResult = new LoadPromptsResult(
                deduped.items(), deduped.diagnostics()
            );
            allDiagnostics.addAll(deduped.diagnostics());
        } catch (Exception e) {
            logger.warn("加载 Prompt 模板时出错: {}", e.getMessage());
            this.promptsResult = new LoadPromptsResult(List.of(), List.of());
        }
    }
    
    /**
     * 加载上下文文件（AGENTS.md / CLAUDE.md）。
     *
     * <p>加载策略：
     * <ol>
     *   <li>先加载全局上下文文件（{agentDir}/AGENTS.md 或 CLAUDE.md）</li>
     *   <li>从当前工作目录开始，逐级向上遍历目录树，直到文件系统根目录</li>
     *   <li>在每级目录中查找 AGENTS.md（优先）或 CLAUDE.md</li>
     *   <li>已发现的路径会被去重（使用已处理路径集合记录）</li>
     *   <li>结果按目录层级从上到下排列（根目录在前，当前目录在后）</li>
     * </ol>
     */
    private void loadContextFiles() {
        List<ContextFile> files = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        // 1. 加载全局上下文文件
        ContextFile globalContext = loadContextFileFromDir(Paths.get(agentDir));
        if (globalContext != null) {
            files.add(globalContext);
            seenPaths.add(globalContext.path());
        }

        // 2. 从 cwd 向上遍历目录树，收集上下文文件
        List<ContextFile> ancestorFiles = new ArrayList<>();
        Path currentDir = Paths.get(cwd).toAbsolutePath().normalize();
        Path root = currentDir.getRoot();

        while (true) {
            ContextFile contextFile = loadContextFileFromDir(currentDir);
            if (contextFile != null && !seenPaths.contains(contextFile.path())) {
                ancestorFiles.add(0, contextFile); // 插到列表头部，保持从根到当前目录的顺序
                seenPaths.add(contextFile.path());
            }

            if (currentDir.equals(root)) break;

            Path parent = currentDir.getParent();
            if (parent == null || parent.equals(currentDir)) break;
            currentDir = parent;
        }

        files.addAll(ancestorFiles);
        this.agentsFiles = List.copyOf(files);
    }

    /**
     * 从指定目录加载上下文文件。
     *
     * <p>优先查找 AGENTS.md，如果不存在则查找 CLAUDE.md。
     * 两个文件同时存在时只加载 AGENTS.md。
     *
     * @param dir 要搜索的目录
     * @return 上下文文件对象，如果目录中不存在任何上下文文件则返回 null
     */
    private ContextFile loadContextFileFromDir(Path dir) {
        for (String filename : CONTEXT_FILE_NAMES) {
            Path filePath = dir.resolve(filename);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    return new ContextFile(filePath.toString(), content);
                } catch (IOException e) {
                    logger.warn("无法读取 {}: {}", filePath, e.getMessage());
                }
            }
        }
        return null;
    }
    
    }

    /**
     * 加载系统提示（SYSTEM.md）。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>项目级：{cwd}/.kiro/SYSTEM.md（优先级高）</li>
     *   <li>全局级：{agentDir}/SYSTEM.md（优先级低）</li>
     * </ol>
     *
     * <p>项目级配置会覆盖全局配置，实现项目级别的定制化。
     */
    private void loadSystemPromptInternal() {
        String prompt = discoverFileContent(SYSTEM_PROMPT_FILE);
        this.systemPrompt = prompt;
    }

    /**
     * 加载附加系统提示（APPEND_SYSTEM.md）。
     *
     * <p>查找顺序同 {@link #loadSystemPromptInternal()}。
     * 如果文件存在且内容非空，则作为多行列表存储。
     *
     * <p>附加系统提示追加在系统提示之后，用于补充额外指令。
     */
    private void loadAppendSystemPromptInternal() {
        String content = discoverFileContent(APPEND_SYSTEM_PROMPT_FILE);
        if (content != null && !content.isBlank()) {
            this.appendSystemPrompt = List.of(content);
        } else {
            this.appendSystemPrompt = List.of();
        }
    }

    /**
     * 在项目级和全局目录中查找指定文件并返回其内容。
     *
     * <p>查找策略：
     * <ol>
     *   <li>先检查项目级目录（{cwd}/.kiro/{filename}），项目配置优先</li>
     *   <li>如果未找到，再检查全局目录（{agentDir}/{filename}）</li>
     * </ol>
     *
     * @param filename 要查找的文件名
     * @return 文件内容，如果文件不存在或读取失败则返回 null
     */
    private String discoverFileContent(String filename) {
        // 优先检查项目级目录
        Path projectFile = Paths.get(cwd, ".kiro", filename);
        if (Files.exists(projectFile) && Files.isRegularFile(projectFile)) {
            try {
                return Files.readString(projectFile);
            } catch (IOException e) {
                logger.warn("无法读取 {}: {}", projectFile, e.getMessage());
            }
        }

        // 然后检查全局目录
        Path globalFile = Paths.get(agentDir, filename);
        if (Files.exists(globalFile) && Files.isRegularFile(globalFile)) {
            try {
                return Files.readString(globalFile);
            } catch (IOException e) {
                logger.warn("无法读取 {}: {}", globalFile, e.getMessage());
            }
        }

        return null;
    }
    
    /**
     * 对 Prompt 模板进行去重。
     *
     * <p>当发现同名模板时，保留第一个出现的，后续同名模板会生成冲突诊断。
     * 使用 {@link LinkedHashMap} 保持插入顺序。
     *
     * @param prompts 待去重的模板列表
     * @return 去重结果，包含唯一的模板列表和冲突诊断信息
     */
    private DedupeResult<PromptTemplate> dedupePrompts(List<PromptTemplate> prompts) {
        Map<String, PromptTemplate> seen = new LinkedHashMap<>();
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        for (PromptTemplate prompt : prompts) {
            PromptTemplate existing = seen.get(prompt.name());
            if (existing != null) {
                diagnostics.add(new ResourceDiagnostic(
                    "collision",
                    "prompt template name \"" + prompt.name() + "\" collision",
                    prompt.filePath(),
                    new ResourceCollision(
                        "prompt",
                        prompt.name(),
                        existing.filePath(),
                        prompt.filePath()
                    )
                ));
            } else {
                seen.put(prompt.name(), prompt);
            }
        }

        return new DedupeResult<>(new ArrayList<>(seen.values()), diagnostics);
    }

    /**
     * 合并路径列表，自动去重并保持顺序。
     *
     * <p>将 additional 中的路径合并到 primary 中，如果路径已存在则跳过。
     * 使用 {@link LinkedHashSet} 保证去重且保持插入顺序。
     *
     * @param primary    主路径列表
     * @param additional 要合并的额外路径列表
     * @return 合并后的路径列表
     */
    private List<String> mergePaths(List<String> primary, List<String> additional) {
        Set<String> seen = new LinkedHashSet<>(primary);
        seen.addAll(additional);
        return new ArrayList<>(seen);
    }

    /**
     * 内部记录类，用于封装去重结果。
     *
     * @param <T>         去重后的元素类型
     * @param items       去重后的元素列表
     * @param diagnostics 去重过程中产生的诊断信息
     */
    private record DedupeResult<T>(
        List<T> items,
        List<ResourceDiagnostic> diagnostics
    ) {}

    // ==================== 热重载支持 ====================

    @Override
    public void addChangeListener(ResourceChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
            logger.debug("添加了资源变化监听器，当前总数: {}", changeListeners.size());
        }
    }

    @Override
    public void removeChangeListener(ResourceChangeListener listener) {
        if (listener != null) {
            changeListeners.remove(listener);
            logger.debug("移除了资源变化监听器，当前总数: {}", changeListeners.size());
        }
    }

    @Override
    public void startWatching() {
        if (skillsWatcher != null) {
            skillsWatcher.start();
            logger.info("已启动资源文件变化监控");
        }
    }

    @Override
    public void stopWatching() {
        if (skillsWatcher != null) {
            skillsWatcher.stop();
            logger.info("已停止资源文件变化监控");
        }
    }

    @Override
    public void dispose() {
        stopWatching();
        changeListeners.clear();
        logger.info("DefaultResourceLoader 已释放");
    }

    /**
     * 初始化 SkillsWatcher 文件监控器。
     *
     * <p>创建 SkillsWatcher 实例，配置监控目录和防抖参数。
     * 监控器创建后不会立即启动，需要调用 {@link #startWatching()} 启动。
     *
     * <p>如果初始化失败，watcher 会被设为 null，后续的 start/stop 操作将无效果。
     */
    private void initializeWatcher() {
        try {
            SkillsWatcherConfig config = new SkillsWatcherConfig(
                agentDir,
                cwd,
                SkillsWatcherConfig.DEFAULT_DEBOUNCE_DELAY_MS,
                this::handleFileChange
            );
            this.skillsWatcher = new SkillsWatcher(config);
            logger.debug("已初始化 SkillsWatcher，agentDir={}, cwd={}", agentDir, cwd);
        } catch (Exception e) {
            logger.warn("初始化 SkillsWatcher 失败: {}", e.getMessage());
            this.skillsWatcher = null;
        }
    }

    /**
     * 处理来自 SkillsWatcher 的文件变化事件。
     *
     * <p>当检测到文件变化时，触发 {@link #reload()} 重载所有资源。
     * 重载完成后记录成功或失败日志。
     */
    private void handleFileChange() {
        logger.debug("检测到文件变化，正在重载资源...");
        reload().whenComplete((result, error) -> {
            if (error != null) {
                logger.error("文件变化后重载资源失败: {}", error.getMessage());
            } else {
                logger.info("文件变化后资源重载成功");
            }
        });
    }

    /**
     * 通知所有注册的监听器资源已发生变化。
     *
     * <p>创建 {@link ResourceChangeEvent} 事件对象，包含最新的 Skills、Prompts
     * 和诊断信息，然后依次通知所有监听器。单个监听器的异常不会影响其他监听器的通知。
     */
    private void notifyListeners() {
        if (changeListeners.isEmpty()) {
            return;
        }

        ResourceChangeEvent event = ResourceChangeEvent.of(
            skillsResult,
            promptsResult,
            diagnostics
        );

        for (ResourceChangeListener listener : changeListeners) {
            try {
                listener.onResourceChanged(event);
            } catch (Exception e) {
                logger.warn("通知资源变化监听器时出错: {}", e.getMessage());
            }
        }

        logger.debug("已通知 {} 个监听器资源变化", changeListeners.size());
    }
}
