package com.pi.coding.session;

import com.pi.agent.Agent;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.*;
import com.pi.ai.core.types.*;
import com.pi.coding.compaction.Compaction;
import com.pi.coding.compaction.CompactionResult;
import com.pi.coding.compaction.CompactionUtils;
import com.pi.coding.extension.ExtensionRunner;
import com.pi.coding.extension.ToolDefinition;
import com.pi.coding.extension.ToolInfo;
import com.pi.coding.message.*;
import com.pi.coding.model.CodingModelRegistry;
import com.pi.coding.prompt.SystemPromptBuilder;
import com.pi.coding.prompt.SystemPromptConfig;
import com.pi.coding.resource.ContextFile;
import com.pi.coding.resource.ResourceChangeEvent;
import com.pi.coding.resource.ResourceChangeListener;
import com.pi.coding.resource.ResourceLoader;
import com.pi.coding.resource.Skill;
import com.pi.coding.settings.RetrySettings;
import com.pi.coding.settings.SettingsManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 高级 Agent 会话，包装 pi-agent-core 的 Agent，增加编码 Agent 特有的功能：
 * 会话持久化、自动压缩、自动重试、模型切换、Bash 执行和扩展集成。
 *
 * <p>AgentSession 是编码 Agent 的核心门面类，提供以下功能：
 * <ul>
 *   <li><b>会话管理</b>：创建、切换、分支（fork）会话，支持会话持久化</li>
 *   <li><b>模型管理</b>：设置模型和思考级别，支持在作用域模型列表中循环切换</li>
 *   <li><b>提示词处理</b>：发送 prompt、引导消息（steer）、跟进消息（followUp）</li>
 *   <li><b>自动压缩</b>：上下文超限时自动压缩旧消息为摘要</li>
 *   <li><b>自动重试</b>：遇到临时错误时自动重试（指数退避）</li>
 *   <li><b>Bash 执行</b>：在工作目录中执行 Bash 命令并记录结果</li>
 *   <li><b>事件通知</b>：通过监听器模式通知 AutoCompaction、AutoRetry 等事件</li>
 *   <li><b>热重载</b>：监听资源变更，自动重建系统提示词</li>
 *   <li><b>扩展集成</b>：支持扩展事件处理和工具注入</li>
 * </ul>
 *
 * <p>验证需求：2.1-2.18
 */
public class AgentSession {

    private static final Logger LOG = Logger.getLogger(AgentSession.class.getName());

    // ---- 配置项 ----
    /** 底层的 pi-agent-core Agent 实例 */
    private final Agent agent;
    /** 会话管理器，负责会话持久化和树形结构管理 */
    private final SessionManager sessionManager;
    /** 设置管理器，管理自动压缩、重试等设置 */
    private final SettingsManager settingsManager;
    /** 当前工作目录 */
    private final String cwd;
    /** 模型注册表，用于查找和获取 API Key */
    private final CodingModelRegistry modelRegistry;
    /** 资源加载器，加载 skills、prompts 等资源 */
    private final ResourceLoader resourceLoader;

    // ---- 运行时状态 ----
    /** 当前使用的模型 */
    private volatile Model currentModel;
    /** 当前思考级别（off / low / medium / high） */
    private volatile String currentThinkingLevel;
    /** 是否正在执行压缩操作 */
    private volatile boolean isCompacting;
    /** 是否启用自动压缩 */
    private volatile boolean autoCompactionEnabled = true;
    /** 是否启用自动重试 */
    private volatile boolean autoRetryEnabled = true;
    /** 引导模式："all" 或 "one_at_a_time" */
    private volatile String steeringMode = "all";
    /** 跟进模式："all" 或 "one_at_a_time" */
    private volatile String followUpMode = "all";
    /** 扩展运行器，负责扩展的事件处理和生命周期 */
    private volatile ExtensionRunner extensionRunner;
    /** 可用于循环切换的模型列表 */
    private List<ScopedModel> scopedModels;
    /** 当前激活的工具名称列表 */
    private List<String> activeToolNames;
    /** 扩展定义的自定义工具定义列表 */
    private final List<ToolDefinition> customTools;

    // ---- 事件监听器 ----
    /** 会话事件监听器列表（线程安全的写时复制列表） */
    private final CopyOnWriteArrayList<Consumer<AgentSessionEvent>> listeners = new CopyOnWriteArrayList<>();
    /** 取消订阅底层 Agent 事件的方法 */
    private Runnable agentUnsubscribe;
    /** 资源变更监听器，用于热重载 */
    private final ResourceChangeListener resourceChangeListener;

    // ---- 重试状态 ----
    /** 当前重试次数 */
    private final AtomicInteger retryAttempt = new AtomicInteger(0);
    /** 当前重试的 Future 对象 */
    private volatile CompletableFuture<Void> retryFuture;

    // ---- 压缩状态 ----
    /** 当前压缩操作的 Future 对象 */
    private volatile CompletableFuture<CompactionResult> compactionFuture;

    // ---- Bash 状态 ----
    /** 当前正在执行的 Bash 进程 */
    private volatile Process currentBashProcess;
    /** Bash 是否正在运行 */
    private final AtomicBoolean bashRunning = new AtomicBoolean(false);
    /** 待刷新的 Bash 执行消息列表（同步列表） */
    private final List<BashExecutionMessage> pendingBashMessages =
            Collections.synchronizedList(new ArrayList<>());

    // =========================================================================
    // 构造方法
    // =========================================================================

    /**
     * 使用给定的配置创建 AgentSession 实例。
     *
     * <p>构造过程会：
     * <ol>
     *   <li>从配置中提取各组件引用</li>
     *   <li>从 Agent 状态初始化模型和思考级别</li>
     *   <li>订阅底层 Agent 的事件</li>
     *   <li>注册资源变更监听器以支持热重载</li>
     * </ol>
     *
     * @param config 会话配置
     */
    public AgentSession(AgentSessionConfig config) {
        this.agent = config.agent();
        this.sessionManager = config.sessionManager();
        this.settingsManager = config.settingsManager();
        this.cwd = config.cwd();
        this.modelRegistry = config.modelRegistry();
        this.resourceLoader = config.resourceLoader();
        this.scopedModels = new ArrayList<>(config.scopedModels());
        this.customTools = new ArrayList<>(config.customTools());
        this.activeToolNames = new ArrayList<>(config.initialActiveToolNames());

        // 从 Agent 状态初始化模型和思考级别
        AgentState state = agent.getState();
        this.currentModel = state.getModel();
        this.currentThinkingLevel = state.getThinkingLevel() != null
                ? state.getThinkingLevel().name().toLowerCase() : "off";

        // 订阅底层 Agent 事件
        this.agentUnsubscribe = agent.subscribe(this::handleAgentEvent);

        // 注册资源变更监听器，支持热重载
        this.resourceChangeListener = this::handleResourceChange;
        if (resourceLoader != null) {
            resourceLoader.addChangeListener(resourceChangeListener);
        }

        // 确保 Agent 状态已初始化
        agent.getState(); // ensure initialized
    }

    /**
     * 处理来自 ResourceLoader 的资源变更事件。
     * 当 skills 或 prompts 发生变化时重建系统提示词。
     *
     * @param event 资源变更事件
     */
    private void handleResourceChange(ResourceChangeEvent event) {
        LOG.info("检测到资源变更，正在重建系统提示词");
        rebuildSystemPrompt();
        emit(new ResourceChangeSessionEvent(event));
    }

    // =========================================================================
    // 事件订阅
    // =========================================================================

    /**
     * 订阅 Agent 会话事件。
     *
     * @param listener 事件监听器
     * @return 一个 Runnable，调用后可取消订阅该监听器
     */
    public Runnable subscribe(Consumer<AgentSessionEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 向所有注册的监听器发送事件。
     * 单个监听器异常不会影响其他监听器的接收。
     *
     * @param event 要发送的事件
     */
    private void emit(AgentSessionEvent event) {
        for (Consumer<AgentSessionEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "会话事件监听器出错", e);
            }
        }
    }

    /**
     * 处理底层 Agent 发出的事件。
     *
     * <p>主要职责：
     * <ul>
     *   <li>将 AgentEvent 转发为 AgentSessionEvent</li>
     *   <li>在消息结束时持久化消息</li>
     *   <li>在 Agent 处理结束时检查自动压缩和自动重试</li>
     * </ul>
     *
     * @param event 底层 Agent 事件
     */
    private void handleAgentEvent(AgentEvent event) {
        // 转发为 AgentSessionEvent（AgentEvent 通过包装类也属于 AgentSessionEvent）
        emit(new AgentEventWrapper(event));

        // 消息结束时持久化消息
        if (event instanceof AgentEvent.MessageEnd me) {
            persistMessage(me.message());
        }

        // Agent 处理结束时检查自动压缩
        if (event instanceof AgentEvent.AgentEnd ae) {
            handleAgentEnd(ae);
        }
    }

    /**
     * 将会话消息持久化到会话管理器。
     *
     * @param message 要持久化的 Agent 消息
     */
    private void persistMessage(AgentMessage message) {
        try {
            sessionManager.appendMessage(message);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "持久化消息失败", e);
        }
    }

    /**
     * 处理 agent_end 事件——检查可重试错误和自动压缩条件。
     *
     * @param event Agent 结束事件
     */
    private void handleAgentEnd(AgentEvent.AgentEnd event) {
        if (event.messages() == null || event.messages().isEmpty()) return;

        // 查找最后一条助手消息
        AgentMessage lastMsg = event.messages().get(event.messages().size() - 1);
        if (lastMsg instanceof MessageAdapter adapter
                && adapter.message() instanceof AssistantMessage assistant) {
            // 检查可重试错误
            if (autoRetryEnabled && isRetryableError(assistant)) {
                handleRetryableError(assistant);
                return;
            }
            // 检查自动压缩条件
            if (autoCompactionEnabled) {
                checkAutoCompaction(assistant);
            }
        }
    }

    // =========================================================================
    // 状态访问器
    // =========================================================================

    /** 获取底层 Agent 的完整状态。 */
    public AgentState getState() { return agent.getState(); }

    /** 获取当前使用的模型。 */
    public Model getModel() { return currentModel; }

    /** 获取当前思考级别。 */
    public String getThinkingLevel() { return currentThinkingLevel; }

    /** 检查 Agent 是否正在流式输出。 */
    public boolean isStreaming() { return agent.getState().isStreaming(); }

    /** 检查是否正在执行压缩。 */
    public boolean isCompacting() { return isCompacting; }

    /** 获取当前工作目录。 */
    public String getCwd() { return cwd; }

    /** 获取会话管理器。 */
    public SessionManager getSessionManager() { return sessionManager; }

    /** 获取设置管理器。 */
    public SettingsManager getSettingsManager() { return settingsManager; }

    /** 获取模型注册表。 */
    public CodingModelRegistry getModelRegistry() { return modelRegistry; }

    /** 获取资源加载器。 */
    public ResourceLoader getResourceLoader() { return resourceLoader; }

    /** 获取引导模式。 */
    public String getSteeringMode() { return steeringMode; }

    /** 获取跟进模式。 */
    public String getFollowUpMode() { return followUpMode; }

    /** 获取当前重试次数。 */
    public int getRetryAttempt() { return retryAttempt.get(); }

    /** 检查是否正在执行重试。 */
    public boolean isRetrying() { return retryFuture != null && !retryFuture.isDone(); }

    /** 检查是否启用了自动重试。 */
    public boolean isAutoRetryEnabled() { return autoRetryEnabled; }

    /** 检查是否启用了自动压缩。 */
    public boolean isAutoCompactionEnabled() { return autoCompactionEnabled; }

    /** 获取当前会话中的所有消息。 */
    public List<AgentMessage> getMessages() { return agent.getState().getMessages(); }

    // =========================================================================
    // 模型和思考级别管理
    // =========================================================================

    /**
     * 设置当前使用的模型，并记录模型变更到会话。
     *
     * @param model 要切换到的模型
     */
    public void setModel(Model model) {
        this.currentModel = model;
        agent.setModel(model);
        sessionManager.appendModelChange(
                model.provider(), model.id());
    }

    /**
     * 循环切换到作用域模型列表中的下一个模型。
     * 如果关联的模型有思考级别设置，也会自动应用。
     *
     * @return 循环切换结果，如果没有可用模型则返回 null
     */
    public ModelCycleResult cycleModel() {
        if (scopedModels.isEmpty()) return null;

        // 查找当前模型在列表中的索引
        int currentIndex = -1;
        for (int i = 0; i < scopedModels.size(); i++) {
            if (scopedModels.get(i).model().equals(currentModel)) {
                currentIndex = i;
                break;
            }
        }

        // 计算下一个模型的索引（循环）
        int nextIndex = (currentIndex + 1) % scopedModels.size();
        ScopedModel next = scopedModels.get(nextIndex);

        // 切换到下一个模型
        setModel(next.model());
        if (next.thinkingLevel() != null) {
            setThinkingLevel(next.thinkingLevel());
        }

        return new ModelCycleResult(next.model(), currentThinkingLevel, null);
    }

    /**
     * 设置思考级别。
     *
     * @param level 思考级别值（"off"、"low"、"medium"、"high"）
     */
    public void setThinkingLevel(String level) {
        this.currentThinkingLevel = level;
        AgentThinkingLevel agentLevel = parseThinkingLevel(level);
        agent.setThinkingLevel(agentLevel);
        sessionManager.appendThinkingLevelChange(level);
    }

    /**
     * 循环切换到下一个思考级别。
     * 顺序：off → low → medium → high → off
     *
     * @return 新的思考级别
     */
    public String cycleThinkingLevel() {
        List<String> levels = List.of("off", "low", "medium", "high");
        int idx = levels.indexOf(currentThinkingLevel);
        String next = levels.get((idx + 1) % levels.size());
        setThinkingLevel(next);
        return next;
    }

    /**
     * 将字符串级别的思考级别解析为 AgentThinkingLevel 枚举值。
     *
     * @param level 字符串表示的思考级别
     * @return 对应的枚举值，默认为 OFF
     */
    private static AgentThinkingLevel parseThinkingLevel(String level) {
        if (level == null) return AgentThinkingLevel.OFF;
        return switch (level.toLowerCase()) {
            case "low" -> AgentThinkingLevel.LOW;
            case "medium" -> AgentThinkingLevel.MEDIUM;
            case "high" -> AgentThinkingLevel.HIGH;
            default -> AgentThinkingLevel.OFF;
        };
    }

    // =========================================================================
    // 提示词和消息队列
    // =========================================================================

    /**
     * 向 Agent 发送提示词消息。
     *
     * <p>发送前会：
     * <ol>
     *   <li>刷新待处理的 Bash 执行消息</li>
     *   <li>根据当前状态重建系统提示词</li>
     * </ol>
     *
     * @param text    提示词文本
     * @param options 提示词选项（可为 null，使用默认值）
     * @return 一个 Future，在 Agent 处理完成时完成
     */
    public CompletableFuture<Void> prompt(String text, PromptOptions options) {
        if (options == null) options = PromptOptions.defaults();

        // 刷新待处理的 Bash 消息
        flushPendingBashMessages();

        // 重建系统提示词
        rebuildSystemPrompt();

        return agent.prompt(text);
    }

    /**
     * 发送引导消息（中断模式）。
     * 消息会立即插入到当前处理之前。
     *
     * @param text 引导消息文本
     */
    public void steer(String text) {
        UserMessage msg = new UserMessage(text, System.currentTimeMillis());
        agent.steer(MessageAdapter.wrap(msg));
    }

    /**
     * 发送跟进消息（等待模式）。
     * 消息会在当前处理完成后执行。
     *
     * @param text 跟进消息文本
     */
    public void followUp(String text) {
        UserMessage msg = new UserMessage(text, System.currentTimeMillis());
        agent.followUp(MessageAdapter.wrap(msg));
    }

    /**
     * 中止当前正在进行的操作，包括压缩和重试。
     */
    public void abort() {
        agent.abort();
        abortCompaction();
        abortRetry();
    }

    // =========================================================================
    // 压缩
    // =========================================================================

    /**
     * 手动触发会话压缩。
     *
     * <p>压缩会将较旧的消息汇总为摘要，以节省 LLM 上下文窗口空间。
     * 压缩后，旧消息会被摘要替换，但原始消息仍保留在会话文件中。
     *
     * @param customInstructions 可选的自定义压缩指令（用于生成摘要）
     * @return 包含压缩结果的 Future
     * @throws IllegalStateException 如果压缩已经在进行中
     */
    public CompletableFuture<CompactionResult> compact(String customInstructions) {
        if (isCompacting) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("压缩操作已在进行中"));
        }

        isCompacting = true;
        CompletableFuture<CompactionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                List<AgentMessage> messages = agent.getState().getMessages();
                int tokenEstimate = CompactionUtils.estimateTokens(messages);
                List<SessionEntry> entries = sessionManager.getEntries();

                // 查找切割点
                var cutPoint = Compaction.findCutPoint(
                        entries, 0, entries.size(),
                        settingsManager.getCompactionSettings().keepRecentTokens());

                if (cutPoint.firstKeptEntryIndex() <= 0) {
                    return null;
                }

                // 获取切割点处的条目 ID
                String firstKeptEntryId = entries.get(cutPoint.firstKeptEntryIndex()).id();

                // 目前返回基本结果
                // 完整实现会调用 SummaryGenerator
                return CompactionResult.of(
                        "压缩摘要占位符",
                        firstKeptEntryId,
                        tokenEstimate);
            } finally {
                isCompacting = false;
            }
        });

        this.compactionFuture = future;
        return future;
    }

    /**
     * 中止当前正在进行的压缩操作。
     */
    public void abortCompaction() {
        CompletableFuture<CompactionResult> f = compactionFuture;
        if (f != null && !f.isDone()) {
            f.cancel(true);
        }
        isCompacting = false;
    }

    /**
     * 检查助手消息后是否需要触发自动压缩。
     *
     * <p>判断依据：当前上下文中的 Token 估计值是否接近模型上下文窗口上限。
     *
     * @param assistant 最后一条助手消息
     */
    private void checkAutoCompaction(AssistantMessage assistant) {
        try {
            List<AgentMessage> messages = agent.getState().getMessages();
            int tokenEstimate = CompactionUtils.estimateTokens(messages);
            Model model = currentModel;
            if (model == null) return;

            int contextWindow = model.contextWindow() > 0 ? model.contextWindow() : 200000;
            int reserveTokens = settingsManager.getCompactionSettings().reserveTokens();

            if (CompactionUtils.shouldCompact(tokenEstimate, contextWindow, reserveTokens)) {
                runAutoCompaction("threshold", false);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "检查自动压缩条件时出错", e);
        }
    }

    /**
     * 执行自动压缩。
     *
     * <p>发送 AutoCompactionStartEvent 和 AutoCompactionEndEvent 事件通知监听器。
     *
     * @param reason    压缩原因（"overflow" 或 "threshold"）
     * @param willRetry 压缩后是否将重试
     */
    private void runAutoCompaction(String reason, boolean willRetry) {
        emit(new AutoCompactionStartEvent(reason));
        try {
            CompletableFuture<CompactionResult> future = compact(null);
            CompactionResult result = future.get(120, TimeUnit.SECONDS);
            emit(new AutoCompactionEndEvent(result, false, willRetry, null));
        } catch (CancellationException e) {
            emit(new AutoCompactionEndEvent(null, true, false, null));
        } catch (Exception e) {
            emit(new AutoCompactionEndEvent(null, false, false, e.getMessage()));
        }
    }

    // =========================================================================
    // 自动重试
    // =========================================================================

    /**
     * 检查助手消息是否表示可重试的错误。
     *
     * <p>可重试的错误包括：过载（overloaded）、速率限制（rate_limit）、
     * 服务器错误（500/503/529）等临时性问题。
     *
     * @param message 助手消息
     * @return 如果可重试则返回 true
     */
    private boolean isRetryableError(AssistantMessage message) {
        if (message.getStopReason() != StopReason.ERROR) return false;
        String errorMsg = message.getErrorMessage();
        if (errorMsg == null) return false;
        String lower = errorMsg.toLowerCase();
        return lower.contains("overloaded")
                || lower.contains("rate_limit")
                || lower.contains("rate limit")
                || lower.contains("server_error")
                || lower.contains("529")
                || lower.contains("503")
                || lower.contains("500");
    }

    /**
     * 使用指数退避策略处理可重试错误。
     *
     * <p>退避算法：delay = min(baseDelay * 2^(attempt-1), maxDelay)
     * 重试次数超过 maxRetries 后放弃。
     *
     * @param message 包含错误的助手消息
     */
    private void handleRetryableError(AssistantMessage message) {
        RetrySettings retry = settingsManager.getRetrySettings();
        if (!retry.enabled()) return;

        int attempt = retryAttempt.incrementAndGet();
        if (attempt > retry.maxRetries()) {
            retryAttempt.set(0);
            return;
        }

        // 计算指数退避延迟
        long delay = Math.min(
                retry.baseDelayMs() * (1L << (attempt - 1)),
                retry.maxDelayMs());

        String reason = message.getErrorMessage() != null ? message.getErrorMessage() : "unknown";
        emit(new AutoRetryStartEvent(attempt, delay, reason));

        // 异步执行重试
        retryFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(delay);
                agent.continueProcessing();
                emit(new AutoRetryEndEvent(attempt, true, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emit(new AutoRetryEndEvent(attempt, false, true));
            } catch (Exception e) {
                emit(new AutoRetryEndEvent(attempt, false, false));
            }
        });
    }

    /**
     * 中止当前正在进行的重试操作。
     */
    public void abortRetry() {
        CompletableFuture<Void> f = retryFuture;
        if (f != null && !f.isDone()) {
            f.cancel(true);
        }
        retryAttempt.set(0);
    }

    /** 启用或禁用自动重试。 */
    public void setAutoRetryEnabled(boolean enabled) { this.autoRetryEnabled = enabled; }

    /** 启用或禁用自动压缩。 */
    public void setAutoCompactionEnabled(boolean enabled) { this.autoCompactionEnabled = enabled; }

    // =========================================================================
    // Bash 执行
    // =========================================================================

    /**
     * 在工作目录中执行 Bash 命令并记录结果。
     *
     * <p>执行结果会暂存在 pendingBashMessages 中，
     * 下次发送 prompt 时自动刷新到消息列表。
     *
     * @param command            要执行的命令
     * @param excludeFromContext 如果为 true，结果不包含在 LLM 上下文中
     * @return 包含 Bash 执行结果的 Future
     */
    public CompletableFuture<BashExecutionMessage> executeBash(
            String command, boolean excludeFromContext) {
        bashRunning.set(true);
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                pb.directory(new java.io.File(cwd));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentBashProcess = process;

                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                BashExecutionMessage msg = new BashExecutionMessage(
                        command, output, exitCode, false, false, null,
                        System.currentTimeMillis(),
                        excludeFromContext ? true : null);

                pendingBashMessages.add(msg);
                return msg;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new BashExecutionMessage(
                        command, "", null, true, false, null,
                        System.currentTimeMillis(),
                        excludeFromContext ? true : null);
            } catch (Exception e) {
                return new BashExecutionMessage(
                        command, e.getMessage(), 1, false, false, null,
                        System.currentTimeMillis(),
                        excludeFromContext ? true : null);
            } finally {
                bashRunning.set(false);
                currentBashProcess = null;
            }
        });
    }

    /**
     * 强制中止当前正在执行的 Bash 进程。
     */
    public void abortBash() {
        Process p = currentBashProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    /** 检查 Bash 是否正在执行。 */
    public boolean isBashRunning() { return bashRunning.get(); }

    /**
     * 将待处理的 Bash 消息刷新到 Agent 的消息列表中。
     * 在发送新 prompt 前自动调用。
     */
    private void flushPendingBashMessages() {
        synchronized (pendingBashMessages) {
            for (BashExecutionMessage msg : pendingBashMessages) {
                agent.appendMessage(msg);
                sessionManager.appendMessage(msg);
            }
            pendingBashMessages.clear();
        }
    }

    // =========================================================================
    // 会话管理
    // =========================================================================

    /**
     * 切换到不同的会话文件（用于恢复历史会话或分支）。
     *
     * <p>切换后重建会话上下文，并用新会话的消息替换 Agent 当前消息列表。
     *
     * @param sessionFile 会话文件路径
     */
    public void switchSession(String sessionFile) {
        sessionManager.setSessionFile(java.nio.file.Path.of(sessionFile));
        // 从新会话重建上下文
        SessionContext ctx = sessionManager.buildSessionContext();
        agent.replaceMessages(ctx.messages());
        if (ctx.model() != null) {
            // 从会话恢复模型
        }
        if (ctx.thinkingLevel() != null) {
            currentThinkingLevel = ctx.thinkingLevel();
        }
    }

    /**
     * 从会话树中的指定条目创建分支。
     *
     * <p>分支操作会将当前叶子指针移动到指定条目，
     * 后续的 append 操作会创建新的子节点，形成分支。
     *
     * @param fromEntryId 要分支的起始条目 ID
     * @return 新的叶子条目 ID
     */
    public String fork(String fromEntryId) {
        sessionManager.setLeaf(fromEntryId);
        SessionContext ctx = sessionManager.buildSessionContext();
        agent.replaceMessages(ctx.messages());
        return sessionManager.getLeafId();
    }

    /**
     * 导航到会话树中的指定条目。
     *
     * @param toEntryId 目标条目 ID
     * @param summarize 是否生成分支摘要
     */
    public void navigateTree(String toEntryId, boolean summarize) {
        sessionManager.setLeaf(toEntryId);
        SessionContext ctx = sessionManager.buildSessionContext();
        agent.replaceMessages(ctx.messages());
    }

    // =========================================================================
    // 工具管理
    // =========================================================================

    /**
     * 获取当前激活的工具名称列表（不可修改）。
     *
     * @return 激活的工具名称列表
     */
    public List<String> getActiveToolNames() {
        return Collections.unmodifiableList(activeToolNames);
    }

    /**
     * 获取所有可用工具的详细信息。
     *
     * @return 工具信息列表
     */
    public List<ToolInfo> getAllTools() {
        List<ToolInfo> result = new ArrayList<>();
        for (AgentTool tool : agent.getState().getTools()) {
            result.add(new ToolInfo(tool.name(), tool.description(), tool.parameters()));
        }
        return result;
    }

    /**
     * 按名称设置激活的工具列表。
     * 设置后重建系统提示词以反映工具变更。
     *
     * @param toolNames 要激活的工具名称列表
     */
    public void setActiveToolsByName(List<String> toolNames) {
        this.activeToolNames = new ArrayList<>(toolNames);
        rebuildSystemPrompt();
    }

    /**
     * 设置可用于循环切换的模型列表。
     *
     * @param models 作用域模型列表
     */
    public void setScopedModels(List<ScopedModel> models) {
        this.scopedModels = new ArrayList<>(models);
    }

    /** 获取作用域模型列表（不可修改）。 */
    public List<ScopedModel> getScopedModels() {
        return Collections.unmodifiableList(scopedModels);
    }

    /** 设置引导模式。 */
    public void setSteeringMode(String mode) { this.steeringMode = mode; }

    /** 设置跟进模式。 */
    public void setFollowUpMode(String mode) { this.followUpMode = mode; }

    // =========================================================================
    // 系统提示词
    // =========================================================================

    /**
     * 根据当前状态重建系统提示词。
     *
     * <p>系统提示词由以下部分组成：
     * <ul>
     *   <li>已加载的技能（skills）</li>
     *   <li>上下文文件（agents files）</li>
     *   <li>自定义系统提示词</li>
     *   <li>追加的系统提示词行</li>
     *   <li>当前激活的工具名称</li>
     * </ul>
     */
    private void rebuildSystemPrompt() {
        try {
            List<Skill> skills = resourceLoader != null && resourceLoader.getSkills() != null
                    ? resourceLoader.getSkills().skills() : List.of();
            List<ContextFile> contextFiles = resourceLoader != null
                    ? resourceLoader.getAgentsFiles() : List.of();
            String customPrompt = resourceLoader != null
                    ? resourceLoader.getSystemPrompt() : null;
            List<String> appendLines = resourceLoader != null
                    ? resourceLoader.getAppendSystemPrompt() : List.of();
            String appendPrompt = appendLines.isEmpty() ? null : String.join("\n", appendLines);

            SystemPromptConfig config = new SystemPromptConfig(
                    cwd, skills, contextFiles, customPrompt, appendPrompt,
                    activeToolNames, Map.of(), List.of());

            String prompt = SystemPromptBuilder.buildSystemPrompt(config);
            agent.setSystemPrompt(prompt);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "重建系统提示词失败", e);
        }
    }

    // =========================================================================
    // 扩展集成
    // =========================================================================

    /**
     * 设置此会话的扩展运行器。
     *
     * @param runner 扩展运行器实例
     */
    public void setExtensionRunner(ExtensionRunner runner) {
        this.extensionRunner = runner;
    }

    /** 获取扩展运行器。 */
    public ExtensionRunner getExtensionRunner() {
        return extensionRunner;
    }

    /**
     * 检查是否有扩展处理指定事件类型。
     *
     * @param eventType 事件类型
     * @return 如果有注册的处理程序则返回 true
     */
    public boolean hasExtensionHandlers(String eventType) {
        ExtensionRunner runner = extensionRunner;
        return runner != null && runner.hasHandlers(eventType);
    }

    // =========================================================================
    // 生命周期
    // =========================================================================

    /**
     * 释放此会话的所有资源。
     *
     * <p>清理操作包括：
     * <ul>
     *   <li>取消订阅 Agent 事件</li>
     *   <li>移除资源变更监听器</li>
     *   <li>清空事件监听器列表</li>
     *   <li>中止正在进行的压缩、重试和 Bash 执行</li>
     *   <li>释放扩展运行器资源</li>
     * </ul>
     */
    public void dispose() {
        if (agentUnsubscribe != null) {
            agentUnsubscribe.run();
            agentUnsubscribe = null;
        }

        // 移除资源变更监听器
        if (resourceLoader != null && resourceChangeListener != null) {
            resourceLoader.removeChangeListener(resourceChangeListener);
        }

        listeners.clear();
        abortCompaction();
        abortRetry();
        abortBash();
        ExtensionRunner runner = extensionRunner;
        if (runner != null) {
            runner.dispose();
        }
    }

    /**
     * 将会话导出为 HTML 格式。
     * 为每条消息生成一个 div 块，包含角色标签和内容。
     *
     * @return HTML 字符串内容
     */
    public String exportToHtml() {
        // 基础 HTML 导出
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><title>Session Export</title></head><body>\n");
        for (AgentMessage msg : agent.getState().getMessages()) {
            html.append("<div class=\"message ").append(msg.role()).append("\">\n");
            html.append("<strong>").append(msg.role()).append("</strong>: ");
            html.append(escapeHtml(msg.toString()));
            html.append("\n</div>\n");
        }
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * HTML 转义工具方法，防止 XSS 攻击。
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // =========================================================================
    // 内部类型
    // =========================================================================

    /**
     * 包装类，将 AgentEvent 转换为 AgentSessionEvent。
     * 使得底层 Agent 的事件也能通过会话事件监听器通知。
     */
    public record AgentEventWrapper(AgentEvent event) implements AgentSessionEvent {
    }

    /**
     * 资源变更事件，当 skills 或 prompts 等资源发生变化时触发。
     * 用于通知监听器系统提示词已重建。
     */
    public record ResourceChangeSessionEvent(ResourceChangeEvent resourceEvent) implements AgentSessionEvent {
    }
}