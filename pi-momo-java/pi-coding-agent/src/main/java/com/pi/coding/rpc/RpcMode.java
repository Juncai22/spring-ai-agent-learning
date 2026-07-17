package com.pi.coding.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pi.ai.core.util.PiAiJson;
import com.pi.coding.session.AgentSession;
import com.pi.coding.session.ModelCycleResult;
import com.pi.coding.session.NewSessionOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RPC 模式：无头模式，通过 JSON 标准输入/输出协议运行。
 *
 * <p>用于将 Agent 嵌入到其他应用程序中。
 * 从标准输入接收 JSON 行格式的命令，向标准输出发送 JSON 行格式的事件和响应。
 *
 * <p>协议说明：
 * <ul>
 *   <li>命令：JSON 对象，包含 {@code type} 字段，可选 {@code id} 用于关联</li>
 *   <li>响应：JSON 对象，包含 {@code type: "response"}、{@code command}、{@code success}，
 *       以及可选的 {@code data}/{@code error}</li>
 *   <li>事件：AgentSessionEvent 对象实时流式输出</li>
 * </ul>
 *
 * <p>验证需求：20.1-20.17
 */
public class RpcMode {

    private static final Logger LOG = Logger.getLogger(RpcMode.class.getName());

    /** 底层的 Agent 会话实例 */
    private final AgentSession session;
    /** 标准输入读取器，用于接收 JSON 行命令 */
    private final BufferedReader reader;
    /** 输出流，用于发送 JSON 行响应和事件 */
    private final OutputStream outputStream;
    /** 运行状态标志，确保 RPC 循环只启动一次 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 取消订阅事件的方法引用 */
    private volatile Runnable eventUnsubscribe;

    /**
     * 创建 RPC 模式处理器。
     *
     * @param session 要控制的 Agent 会话
     * @param stdin   输入流，用于接收 JSON 行命令
     * @param stdout  输出流，用于发送 JSON 行响应和事件
     */
    public RpcMode(AgentSession session, InputStream stdin, OutputStream stdout) {
        this.session = session;
        this.reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        this.outputStream = stdout;
    }

    // =========================================================================
    // 生命周期管理
    // =========================================================================

    /**
     * 启动 RPC 事件循环。阻塞调用线程，直到调用 {@link #stop()} 或输入流关闭。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("RPC mode already running");
        }

        // 订阅 Agent 会话事件，并将其转发为 JSON 行输出
        eventUnsubscribe = session.subscribe(event -> {
            String eventType = event.getClass().getSimpleName();
            // 将驼峰式类名转换为蛇形事件类型名称
            String snakeType = camelToSnake(eventType);
            emitEvent(snakeType, event);
        });

        // 从标准输入读取 JSON 行
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                handleLine(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                LOG.log(Level.WARNING, "从标准输入读取时出错", e);
            }
        } finally {
            running.set(false);
            if (eventUnsubscribe != null) {
                eventUnsubscribe.run();
                eventUnsubscribe = null;
            }
        }
    }

    /**
     * 停止 RPC 事件循环。
     */
    public void stop() {
        running.set(false);
        try {
            reader.close();
        } catch (IOException e) {
            // 忽略关闭时的错误
        }
    }

    // =========================================================================
    // 行处理
    // =========================================================================

    /**
     * 处理来自标准输入的单行 JSON 命令。
     *
     * @param line 从标准输入读取的 JSON 行
     */
    private void handleLine(String line) {
        try {
            RpcCommand command = PiAiJson.MAPPER.readValue(line, RpcCommand.class);
            RpcResponse response = handleCommand(command);
            emitResponse(response);
        } catch (JsonProcessingException e) {
            emitResponse(RpcResponse.error(null, "parse",
                    "解析命令失败: " + e.getMessage()));
        }
    }

    // =========================================================================
    // 命令分发
    // =========================================================================

    /**
     * 处理单个 RPC 命令并返回响应。
     *
     * <p>根据命令类型分发到对应的处理方法。支持以下命令类别：
     * 提示词、会话管理、模型切换、思考级别、队列模式、压缩、重试、Bash 执行
     *
     * @param command 解析后的命令对象
     * @return 要发送回客户端的响应
     */
    RpcResponse handleCommand(RpcCommand command) {
        String id = command.id();
        String type = command.type();

        try {
            // 提示词相关
            if (command instanceof RpcCommand.Prompt cmd) return handlePrompt(id, cmd);
            if (command instanceof RpcCommand.Steer cmd) return handleSteer(id, cmd);
            if (command instanceof RpcCommand.FollowUp cmd) return handleFollowUp(id, cmd);
            if (command instanceof RpcCommand.Abort) return handleAbort(id);

            // 会话管理
            if (command instanceof RpcCommand.NewSession cmd) return handleNewSession(id, cmd);
            if (command instanceof RpcCommand.GetState) return handleGetState(id);

            // 模型管理
            if (command instanceof RpcCommand.SetModel cmd) return handleSetModel(id, cmd);
            if (command instanceof RpcCommand.CycleModel) return handleCycleModel(id);

            // 思考级别
            if (command instanceof RpcCommand.SetThinkingLevel cmd) return handleSetThinkingLevel(id, cmd);
            if (command instanceof RpcCommand.CycleThinkingLevel) return handleCycleThinkingLevel(id);

            // 队列模式
            if (command instanceof RpcCommand.SetSteeringMode cmd) return handleSetSteeringMode(id, cmd);
            if (command instanceof RpcCommand.SetFollowUpMode cmd) return handleSetFollowUpMode(id, cmd);

            // 压缩
            if (command instanceof RpcCommand.Compact cmd) return handleCompact(id, cmd);
            if (command instanceof RpcCommand.SetAutoCompaction cmd) return handleSetAutoCompaction(id, cmd);

            // 重试
            if (command instanceof RpcCommand.SetAutoRetry cmd) return handleSetAutoRetry(id, cmd);
            if (command instanceof RpcCommand.AbortRetry) return handleAbortRetry(id);

            // Bash 命令
            if (command instanceof RpcCommand.Bash cmd) return handleBash(id, cmd);
            if (command instanceof RpcCommand.AbortBash) return handleAbortBash(id);

            // 会话管理
            if (command instanceof RpcCommand.GetSessionStats) return handleGetSessionStats(id);
            if (command instanceof RpcCommand.ExportHtml cmd) return handleExportHtml(id, cmd);
            if (command instanceof RpcCommand.SwitchSession cmd) return handleSwitchSession(id, cmd);
            if (command instanceof RpcCommand.Fork cmd) return handleFork(id, cmd);

            // 消息与命令
            if (command instanceof RpcCommand.GetMessages) return handleGetMessages(id);
            if (command instanceof RpcCommand.GetCommands) return handleGetCommands(id);

            return RpcResponse.error(id, type, "未知命令类型: " + type);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "处理命令时出错: " + type, e);
            return RpcResponse.error(id, type, e.getMessage());
        }
    }

    // =========================================================================
    // 命令处理器
    // =========================================================================

    /**
     * 处理 prompt 命令：向 Agent 发送提示词。
     * 不阻塞——事件会通过异步流式输出。
     */
    private RpcResponse handlePrompt(String id, RpcCommand.Prompt cmd) {
        // 不阻塞——事件会通过异步流式输出
        session.prompt(cmd.message(), null)
                .exceptionally(e -> {
                    emitResponse(RpcResponse.error(id, "prompt", e.getMessage()));
                    return null;
                });
        return RpcResponse.success(id, "prompt");
    }

    /**
     * 处理 steer 命令：发送引导消息（中断模式）。
     */
    private RpcResponse handleSteer(String id, RpcCommand.Steer cmd) {
        session.steer(cmd.message());
        return RpcResponse.success(id, "steer");
    }

    /**
     * 处理 follow_up 命令：发送跟进消息（等待模式）。
     */
    private RpcResponse handleFollowUp(String id, RpcCommand.FollowUp cmd) {
        session.followUp(cmd.message());
        return RpcResponse.success(id, "follow_up");
    }

    /**
     * 处理 abort 命令：中止当前操作。
     */
    private RpcResponse handleAbort(String id) {
        session.abort();
        return RpcResponse.success(id, "abort");
    }

    /**
     * 处理 new_session 命令：通过会话管理器创建新会话。
     */
    private RpcResponse handleNewSession(String id, RpcCommand.NewSession cmd) {
        NewSessionOptions opts = cmd.parentSession() != null
                ? NewSessionOptions.withParent(cmd.parentSession())
                : null;
        session.getSessionManager().newSession(opts);
        return RpcResponse.success(id, "new_session",
                Map.of("cancelled", false));
    }

    /**
     * 处理 get_state 命令：获取当前会话状态信息。
     */
    private RpcResponse handleGetState(String id) {
        return RpcResponse.success(id, "get_state", Map.of(
                "model", session.getModel() != null ? session.getModel() : "unknown",
                "thinkingLevel", session.getThinkingLevel(),
                "isStreaming", session.isStreaming(),
                "isCompacting", session.isCompacting(),
                "steeringMode", session.getSteeringMode(),
                "followUpMode", session.getFollowUpMode(),
                "autoCompactionEnabled", session.isAutoCompactionEnabled(),
                "messageCount", session.getMessages().size()
        ));
    }

    /**
     * 处理 set_model 命令：切换当前使用的模型。
     * 在模型注册表中查找指定的 provider/model 组合。
     */
    private RpcResponse handleSetModel(String id, RpcCommand.SetModel cmd) {
        var registry = session.getModelRegistry();
        var model = registry.find(cmd.provider(), cmd.modelId());
        if (model == null) {
            return RpcResponse.error(id, "set_model",
                    "未找到模型: " + cmd.provider() + "/" + cmd.modelId());
        }
        session.setModel(model);
        return RpcResponse.success(id, "set_model", model);
    }

    /**
     * 处理 cycle_model 命令：循环切换到列表中的下一个模型。
     */
    private RpcResponse handleCycleModel(String id) {
        ModelCycleResult result = session.cycleModel();
        return RpcResponse.success(id, "cycle_model", result);
    }

    /**
     * 处理 set_thinking_level 命令：设置思考级别。
     */
    private RpcResponse handleSetThinkingLevel(String id, RpcCommand.SetThinkingLevel cmd) {
        session.setThinkingLevel(cmd.level());
        return RpcResponse.success(id, "set_thinking_level");
    }

    /**
     * 处理 cycle_thinking_level 命令：循环切换思考级别（off/low/medium/high）。
     */
    private RpcResponse handleCycleThinkingLevel(String id) {
        String level = session.cycleThinkingLevel();
        return RpcResponse.success(id, "cycle_thinking_level",
                Map.of("level", level));
    }

    /**
     * 处理 set_steering_mode 命令：设置引导模式。
     */
    private RpcResponse handleSetSteeringMode(String id, RpcCommand.SetSteeringMode cmd) {
        session.setSteeringMode(cmd.mode());
        return RpcResponse.success(id, "set_steering_mode");
    }

    /**
     * 处理 set_follow_up_mode 命令：设置跟进模式。
     */
    private RpcResponse handleSetFollowUpMode(String id, RpcCommand.SetFollowUpMode cmd) {
        session.setFollowUpMode(cmd.mode());
        return RpcResponse.success(id, "set_follow_up_mode");
    }

    /**
     * 处理 compact 命令：手动触发会话压缩，将旧消息汇总为摘要。
     */
    private RpcResponse handleCompact(String id, RpcCommand.Compact cmd) {
        try {
            var result = session.compact(cmd.customInstructions()).get();
            return RpcResponse.success(id, "compact", result);
        } catch (Exception e) {
            return RpcResponse.error(id, "compact", e.getMessage());
        }
    }

    /**
     * 处理 set_auto_compaction 命令：启用或禁用自动压缩。
     */
    private RpcResponse handleSetAutoCompaction(String id, RpcCommand.SetAutoCompaction cmd) {
        session.setAutoCompactionEnabled(cmd.enabled());
        return RpcResponse.success(id, "set_auto_compaction");
    }

    /**
     * 处理 set_auto_retry 命令：启用或禁用自动重试。
     */
    private RpcResponse handleSetAutoRetry(String id, RpcCommand.SetAutoRetry cmd) {
        session.setAutoRetryEnabled(cmd.enabled());
        return RpcResponse.success(id, "set_auto_retry");
    }

    /**
     * 处理 abort_retry 命令：中止当前自动重试。
     */
    private RpcResponse handleAbortRetry(String id) {
        session.abortRetry();
        return RpcResponse.success(id, "abort_retry");
    }

    /**
     * 处理 bash 命令：执行 Bash 命令并返回结果。
     */
    private RpcResponse handleBash(String id, RpcCommand.Bash cmd) {
        try {
            var result = session.executeBash(cmd.command(), false).get();
            return RpcResponse.success(id, "bash", result);
        } catch (Exception e) {
            return RpcResponse.error(id, "bash", e.getMessage());
        }
    }

    /**
     * 处理 abort_bash 命令：中止当前正在执行的 Bash 命令。
     */
    private RpcResponse handleAbortBash(String id) {
        session.abortBash();
        return RpcResponse.success(id, "abort_bash");
    }

    /**
     * 处理 get_session_stats 命令：获取会话统计信息。
     */
    private RpcResponse handleGetSessionStats(String id) {
        return RpcResponse.success(id, "get_session_stats", Map.of(
                "messageCount", session.getMessages().size(),
                "isStreaming", session.isStreaming(),
                "isCompacting", session.isCompacting(),
                "retryAttempt", session.getRetryAttempt()
        ));
    }

    /**
     * 处理 export_html 命令：将会话导出为 HTML 格式。
     */
    private RpcResponse handleExportHtml(String id, RpcCommand.ExportHtml cmd) {
        String html = session.exportToHtml();
        return RpcResponse.success(id, "export_html", Map.of("html", html));
    }

    /**
     * 处理 switch_session 命令：切换到不同的会话文件（用于恢复和分支）。
     */
    private RpcResponse handleSwitchSession(String id, RpcCommand.SwitchSession cmd) {
        try {
            session.switchSession(cmd.sessionPath());
            return RpcResponse.success(id, "switch_session",
                    Map.of("cancelled", false));
        } catch (Exception e) {
            return RpcResponse.error(id, "switch_session", e.getMessage());
        }
    }

    /**
     * 处理 fork 命令：从会话树中的指定条目创建分支。
     */
    private RpcResponse handleFork(String id, RpcCommand.Fork cmd) {
        try {
            String leafId = session.fork(cmd.entryId());
            return RpcResponse.success(id, "fork",
                    Map.of("leafId", leafId, "cancelled", false));
        } catch (Exception e) {
            return RpcResponse.error(id, "fork", e.getMessage());
        }
    }

    /**
     * 处理 get_messages 命令：获取当前会话中的所有消息。
     */
    private RpcResponse handleGetMessages(String id) {
        return RpcResponse.success(id, "get_messages",
                Map.of("messages", session.getMessages()));
    }

    /**
     * 处理 get_commands 命令：获取可用的技能和提示模板列表。
     */
    private RpcResponse handleGetCommands(String id) {
        // 获取可用的技能和提示模板
        var skills = session.getResourceLoader() != null
                && session.getResourceLoader().getSkills() != null
                ? session.getResourceLoader().getSkills().skills() : List.of();

        var prompts = session.getResourceLoader() != null
                && session.getResourceLoader().getPrompts() != null
                ? session.getResourceLoader().getPrompts().prompts() : List.of();

        return RpcResponse.success(id, "get_commands",
                Map.of("skills", skills, "prompts", prompts));
    }

    // =========================================================================
    // 输出辅助方法
    // =========================================================================

    /**
     * 向标准输出写入 JSON 行响应。
     * 使用 synchronized 保证多线程安全。
     */
    private synchronized void emitResponse(RpcResponse response) {
        writeJsonLine(response);
    }

    /**
     * 向标准输出写入 JSON 行事件。
     * 使用 synchronized 保证多线程安全。
     */
    private synchronized void emitEvent(String eventType, Object data) {
        writeJsonLine(RpcEvent.of(eventType, data));
    }

    /**
     * 将对象序列化为 JSON 行并写入输出流。
     * 每行末尾追加换行符，以符合 JSON Lines 格式规范。
     */
    private void writeJsonLine(Object obj) {
        try {
            String json = PiAiJson.MAPPER.writeValueAsString(obj);
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "向标准输出写入 JSON 行时出错", e);
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 将驼峰式类名转换为蛇形事件类型名称。
     * 例如："AutoCompactionStartEvent" → "auto_compaction_start_event"
     *
     * @param camelCase 驼峰式字符串
     * @return 蛇形字符串
     */
    static String camelToSnake(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}