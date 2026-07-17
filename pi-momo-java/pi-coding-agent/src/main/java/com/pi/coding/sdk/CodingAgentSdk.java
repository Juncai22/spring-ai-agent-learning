package com.pi.coding.sdk;

import com.pi.agent.Agent;
import com.pi.agent.config.GetApiKeyFunction;
import com.pi.agent.config.StreamFn;
import com.pi.agent.types.AgentTool;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.Transport;
import com.pi.agent.types.AgentOptions;
import com.pi.agent.types.QueueMode;
import com.pi.agent.types.ToolExecutionMode;
import com.pi.coding.auth.AuthStorage;
import com.pi.coding.extension.ExtensionFactory;
import com.pi.coding.extension.ExtensionRunner;
import com.pi.coding.extension.LoadExtensionsResult;
import com.pi.coding.extension.ToolDefinition;
import com.pi.coding.model.CodingModelRegistry;
import com.pi.coding.resource.DefaultResourceLoader;
import com.pi.coding.resource.ResourceLoader;
import com.pi.coding.resource.ResourceLoaderConfig;
import com.pi.coding.session.AgentSession;
import com.pi.coding.session.AgentSessionConfig;
import com.pi.coding.session.ScopedModel;
import com.pi.coding.session.SessionManager;
import com.pi.coding.settings.SettingsManager;
import com.pi.coding.tool.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * SDK 入口点：用于创建和配置编码 Agent 会话。
 *
 * <p>使用 {@link #builder()} 构建器模式，将所有必要组件自动装配在一起，
 * 创建一个完整的 {@link AgentSession} 实例。
 * 构建器会智能地填充默认值，同时也允许自定义每个组件。
 *
 * <p>主要功能：
 * <ul>
 *   <li>创建 Agent 会话，自动装配模型注册表、会话管理器、资源加载器等</li>
 *   <li>提供内置编码工具（读、写、编辑、Bash、Grep、Find、Ls）</li>
 *   <li>支持扩展加载和自定义工具注入</li>
 *   <li>支持模型切换和思考级别配置</li>
 *   <li>支持资源热重载（skills、prompts 变更时自动重建系统提示词）</li>
 * </ul>
 *
 * <p>验证需求：21.1-21.13
 */
public final class CodingAgentSdk {

    private static final Logger LOG = Logger.getLogger(CodingAgentSdk.class.getName());
    /** 默认 Agent 配置目录：~/.pi */
    private static final String DEFAULT_AGENT_DIR = resolveDefaultAgentDir();

    private CodingAgentSdk() {}

    // =========================================================================
    // 创建结果
    // =========================================================================

    /**
     * 通过 SDK 创建 Agent 会话的结果。
     *
     * @param session              创建后的 Agent 会话
     * @param extensionsResult     扩展加载结果（可能包含错误）
     * @param modelFallbackMessage 如果请求的模型不可用时的降级提示信息
     */
    public record CreateResult(
            AgentSession session,
            LoadExtensionsResult extensionsResult,
            String modelFallbackMessage
    ) {}

    // =========================================================================
    // 构建器
    // =========================================================================

    /**
     * 创建 CodingAgentSdk 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * CodingAgentSdk 的构建器类。
     *
     * <p>使用构建器模式组装 AgentSession 所需的所有组件。
     * 未显式设置的组件会自动创建合理的默认实现。
     */
    public static final class Builder {
        /** 当前工作目录 */
        private String cwd;
        /** Agent 配置目录 */
        private String agentDir;
        /** 认证存储，用于管理凭证 */
        private AuthStorage authStorage;
        /** 模型注册表，用于查找和切换模型 */
        private CodingModelRegistry modelRegistry;
        /** 会话管理器，负责会话持久化 */
        private SessionManager sessionManager;
        /** 设置管理器 */
        private SettingsManager settingsManager;
        /** 资源加载器，用于加载 skills、prompts 等 */
        private ResourceLoader resourceLoader;
        /** LLM 流式调用函数 */
        private StreamFn streamFn;
        /** 初始模型 */
        private Model initialModel;
        /** 初始思考级别 */
        private String initialThinkingLevel;
        /** 可用于循环切换的模型列表 */
        private List<ScopedModel> scopedModels;
        /** 内置工具列表 */
        private List<AgentTool> builtinTools;
        /** 扩展定义的自定义工具 */
        private List<ToolDefinition<?>> customTools;
        /** 扩展工厂列表 */
        private List<ExtensionFactory> extensionFactories;
        /** 初始激活的工具名称列表 */
        private List<String> initialActiveToolNames;
        /** 传输层配置 */
        private Transport transport;
        /** 思考预算配置 */
        private ThinkingBudgets thinkingBudgets;

        Builder() {}

        /** 设置当前工作目录。默认为 {@code System.getProperty("user.dir")}。 */
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }

        /** 设置 Agent 配置目录。默认为 {@code ~/.pi}。 */
        public Builder agentDir(String agentDir) { this.agentDir = agentDir; return this; }

        /** 设置认证存储。未设置时自动创建内存存储。 */
        public Builder authStorage(AuthStorage authStorage) { this.authStorage = authStorage; return this; }

        /** 设置模型注册表。未设置时自动创建。 */
        public Builder modelRegistry(CodingModelRegistry modelRegistry) { this.modelRegistry = modelRegistry; return this; }

        /** 设置会话管理器。未设置时自动创建。 */
        public Builder sessionManager(SessionManager sessionManager) { this.sessionManager = sessionManager; return this; }

        /** 设置设置管理器。未设置时自动创建内存实现。 */
        public Builder settingsManager(SettingsManager settingsManager) { this.settingsManager = settingsManager; return this; }

        /** 设置资源加载器。未设置时创建默认实现。 */
        public Builder resourceLoader(ResourceLoader resourceLoader) { this.resourceLoader = resourceLoader; return this; }

        /** 设置 LLM 流式调用函数。此为必需项。 */
        public Builder streamFn(StreamFn streamFn) { this.streamFn = streamFn; return this; }

        /** 设置初始模型。如果不可用则降级到第一个可用模型。 */
        public Builder initialModel(Model model) { this.initialModel = model; return this; }

        /** 设置初始思考级别（如 "none"、"low"、"medium"、"high"）。 */
        public Builder initialThinkingLevel(String level) { this.initialThinkingLevel = level; return this; }

        /** 设置可用于循环切换的模型列表。 */
        public Builder scopedModels(List<ScopedModel> models) { this.scopedModels = models; return this; }

        /** 设置内置工具。默认为 {@link #createCodingTools(String)} 创建的编码工具集。 */
        public Builder builtinTools(List<AgentTool> tools) { this.builtinTools = tools; return this; }

        /** 设置扩展定义的自定义工具定义。 */
        public Builder customTools(List<ToolDefinition<?>> tools) { this.customTools = tools; return this; }

        /** 设置要加载的扩展工厂列表。 */
        public Builder extensionFactories(List<ExtensionFactory> factories) { this.extensionFactories = factories; return this; }

        /** 设置初始激活的工具名称列表。 */
        public Builder initialActiveToolNames(List<String> names) { this.initialActiveToolNames = names; return this; }

        /** 设置传输层配置。 */
        public Builder transport(Transport transport) { this.transport = transport; return this; }

        /** 设置思考预算配置。 */
        public Builder thinkingBudgets(ThinkingBudgets budgets) { this.thinkingBudgets = budgets; return this; }

        /**
         * 构建并返回配置完成的 Agent 会话。
         *
         * <p>此方法执行以下操作：
         * <ol>
         *   <li>解析默认值（cwd、agentDir 等）</li>
         *   <li>创建或使用提供的认证存储、模型注册表、设置管理器、会话管理器</li>
         *   <li>创建或使用提供的资源加载器</li>
         *   <li>解析模型（从初始模型、会话恢复或第一个可用模型）</li>
         *   <li>创建底层 Agent 并配置工具</li>
         *   <li>加载扩展</li>
         *   <li>构建 AgentSession 并应用思考级别</li>
         *   <li>启动资源热重载监听</li>
         * </ol>
         *
         * @return 包含会话和元数据的创建结果
         */
        public CreateResult build() {
            // 解析默认值
            String effectiveCwd = cwd != null ? cwd : System.getProperty("user.dir");
            String effectiveAgentDir = agentDir != null ? agentDir : DEFAULT_AGENT_DIR;

            // 认证存储
            AuthStorage effectiveAuth = authStorage != null
                    ? authStorage : AuthStorage.inMemory();

            // 模型注册表
            CodingModelRegistry effectiveRegistry = modelRegistry != null
                    ? modelRegistry : new CodingModelRegistry(effectiveAuth);

            // 设置管理器
            SettingsManager effectiveSettings = settingsManager != null
                    ? settingsManager : SettingsManager.inMemory();

            // 会话管理器
            SessionManager effectiveSessionManager = sessionManager != null
                    ? sessionManager : SessionManager.inMemory(effectiveCwd);

            // 资源加载器
            ResourceLoader effectiveResourceLoader = resourceLoader;
            if (effectiveResourceLoader == null) {
                effectiveResourceLoader = new DefaultResourceLoader(
                        new ResourceLoaderConfig(effectiveCwd, effectiveAgentDir, effectiveSettings));
            }

            // 工具
            List<AgentTool> tools = builtinTools != null
                    ? new ArrayList<>(builtinTools)
                    : new ArrayList<>(createCodingTools(effectiveCwd));

            // 解析模型
            String modelFallbackMessage = null;
            Model resolvedModel = initialModel;
            if (resolvedModel == null) {
                // 尝试从现有会话恢复模型
                var ctx = effectiveSessionManager.buildSessionContext();
                if (ctx != null && ctx.model() != null) {
                    resolvedModel = effectiveRegistry.find(
                            ctx.model().provider(), ctx.model().modelId());
                }
            }
            if (resolvedModel == null) {
                // 降级到第一个可用模型
                List<Model> available = effectiveRegistry.getAvailableModels();
                if (!available.isEmpty()) {
                    resolvedModel = available.get(0);
                    if (initialModel != null) {
                        modelFallbackMessage = "请求的模型 " + initialModel.id()
                                + " 不可用，使用 " + resolvedModel.id();
                    }
                }
            }

            // 解析思考级别
            String resolvedThinkingLevel = initialThinkingLevel;
            if (resolvedThinkingLevel == null) {
                var ctx = effectiveSessionManager.buildSessionContext();
                resolvedThinkingLevel = ctx != null && ctx.thinkingLevel() != null
                        ? ctx.thinkingLevel() : "none";
            }

            // 将 API Key 获取函数绑定到模型注册表
            GetApiKeyFunction getApiKey = effectiveRegistry::getApiKeyForProvider;

            // 构建底层 Agent
            AgentOptions agentOpts = AgentOptions.builder()
                    .streamFn(streamFn)
                    .getApiKey(getApiKey)
                    .transport(transport)
                    .thinkingBudgets(thinkingBudgets != null
                            ? thinkingBudgets : convertBudgets(effectiveSettings.getThinkingBudgets()))
                    .toolExecution(ToolExecutionMode.PARALLEL)
                    .steeringMode(QueueMode.ALL)
                    .followUpMode(QueueMode.ONE_AT_A_TIME)
                    .build();

            Agent agent = new Agent(agentOpts);
            agent.setTools(tools);
            if (resolvedModel != null) {
                agent.setModel(resolvedModel);
            }

            // 加载扩展
            ExtensionRunner extensionRunner = new ExtensionRunner();
            LoadExtensionsResult extResult = new LoadExtensionsResult(List.of(), List.of());
            if (extensionFactories != null && !extensionFactories.isEmpty()) {
                extResult = extensionRunner.loadExtensions(extensionFactories);
            }

            // 构建 AgentSession
            @SuppressWarnings("unchecked")
            List<ToolDefinition<?>> effectiveCustomTools = customTools != null
                    ? customTools : List.of();

            AgentSessionConfig config = new AgentSessionConfig(
                    agent,
                    effectiveSessionManager,
                    effectiveSettings,
                    effectiveCwd,
                    scopedModels != null ? scopedModels : List.of(),
                    effectiveResourceLoader,
                    (List) effectiveCustomTools,
                    effectiveRegistry,
                    initialActiveToolNames != null ? initialActiveToolNames : List.of()
            );

            AgentSession session = new AgentSession(config);
            session.setExtensionRunner(extensionRunner);

            // 应用解析后的思考级别
            session.setThinkingLevel(resolvedThinkingLevel);

            // 启动资源热重载监听
            effectiveResourceLoader.startWatching();

            return new CreateResult(session, extResult, modelFallbackMessage);
        }
    }

    // =========================================================================
    // 工具工厂方法（需求 21.12）
    // =========================================================================

    /** 创建指定工作目录的 ReadTool。 */
    public static ReadTool createReadTool(String cwd) { return new ReadTool(cwd); }

    /** 创建指定工作目录的 BashTool。 */
    public static BashTool createBashTool(String cwd) { return new BashTool(cwd); }

    /** 创建指定工作目录的 EditTool。 */
    public static EditTool createEditTool(String cwd) { return new EditTool(cwd); }

    /** 创建指定工作目录的 WriteTool。 */
    public static WriteTool createWriteTool(String cwd) { return new WriteTool(cwd); }

    /** 创建指定工作目录的 GrepTool。 */
    public static GrepTool createGrepTool(String cwd) { return new GrepTool(cwd); }

    /** 创建指定工作目录的 FindTool。 */
    public static FindTool createFindTool(String cwd) { return new FindTool(cwd); }

    /** 创建指定工作目录的 LsTool。 */
    public static LsTool createLsTool(String cwd) { return new LsTool(cwd); }

    /**
     * 创建完整的编码工具集（read、write、edit、bash、grep、find、ls）。
     *
     * @param cwd 当前工作目录
     * @return 所有编码工具的列表
     */
    public static List<AgentTool> createCodingTools(String cwd) {
        return List.of(
                createReadTool(cwd),
                createWriteTool(cwd),
                createEditTool(cwd),
                createBashTool(cwd),
                createGrepTool(cwd),
                createFindTool(cwd),
                createLsTool(cwd)
        );
    }

    /**
     * 创建只读工具子集（read、grep、find、ls）。
     *
     * @param cwd 当前工作目录
     * @return 只读工具列表
     */
    public static List<AgentTool> createReadOnlyTools(String cwd) {
        return List.of(
                createReadTool(cwd),
                createGrepTool(cwd),
                createFindTool(cwd),
                createLsTool(cwd)
        );
    }

    // =========================================================================
    // 预构建工具常量（需求 21.13）
    // =========================================================================

    /** 预构建的 ReadTool，使用当前工作目录。 */
    public static final ReadTool READ_TOOL = createReadTool(System.getProperty("user.dir"));

    /** 预构建的 BashTool，使用当前工作目录。 */
    public static final BashTool BASH_TOOL = createBashTool(System.getProperty("user.dir"));

    /** 预构建的 EditTool，使用当前工作目录。 */
    public static final EditTool EDIT_TOOL = createEditTool(System.getProperty("user.dir"));

    /** 预构建的 WriteTool，使用当前工作目录。 */
    public static final WriteTool WRITE_TOOL = createWriteTool(System.getProperty("user.dir"));

    /** 预构建的 GrepTool，使用当前工作目录。 */
    public static final GrepTool GREP_TOOL = createGrepTool(System.getProperty("user.dir"));

    /** 预构建的 FindTool，使用当前工作目录。 */
    public static final FindTool FIND_TOOL = createFindTool(System.getProperty("user.dir"));

    /** 预构建的 LsTool，使用当前工作目录。 */
    public static final LsTool LS_TOOL = createLsTool(System.getProperty("user.dir"));

    /** 预构建的完整编码工具集，使用当前工作目录。 */
    public static final List<AgentTool> CODING_TOOLS = createCodingTools(System.getProperty("user.dir"));

    /** 预构建的只读工具集，使用当前工作目录。 */
    public static final List<AgentTool> READ_ONLY_TOOLS = createReadOnlyTools(System.getProperty("user.dir"));

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 将编码 Agent 的 ThinkingBudgets 转换为 pi-ai-core 的 ThinkingBudgets。
     *
     * @param src 编码 Agent 的思考预算设置
     * @return pi-ai-core 的 ThinkingBudgets 实例
     */
    private static ThinkingBudgets convertBudgets(com.pi.coding.settings.ThinkingBudgets src) {
        if (src == null) return null;
        return new ThinkingBudgets(null, src.low(), src.medium(), src.high());
    }

    /**
     * 解析默认的 Agent 配置目录路径。
     *
     * @return ~/.pi 目录的绝对路径
     */
    private static String resolveDefaultAgentDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".pi").toString();
    }
}