package com.pi.coding.extension;

import com.pi.ai.core.types.Model;
import com.pi.coding.session.SessionManager;

/**
 * 扩展上下文接口 —— 传递给扩展事件处理器的运行时上下文。
 *
 * <p>扩展上下文是事件处理器运行时环境的抽象，提供以下能力：
 * <ul>
 *   <li><b>环境信息</b>：当前工作目录、系统提示词</li>
 *   <li><b>会话访问</b>：获取会话管理器，用于操作会话状态</li>
 *   <li><b>模型信息</b>：获取当前使用的模型</li>
 *   <li><b>Agent 控制</b>：检查 Agent 是否空闲、中止当前操作、优雅关闭</li>
 *   <li><b>上下文管理</b>：获取上下文使用情况、触发上下文压缩</li>
 *   <li><b>UI 状态</b>：检查 UI 是否可用</li>
 * </ul>
 *
 * <p>此接口提供了只读的会话访问，写操作需要通过 {@link ExtensionCommandContext} 完成。
 */
public interface ExtensionContext {

    /**
     * 获取当前工作目录。
     *
     * <p>返回 Agent 当前的工作目录路径，用于文件操作和路径解析。
     *
     * @return 当前工作目录的绝对路径
     */
    String getCwd();

    /**
     * 获取会话管理器（只读）。
     *
     * <p>会话管理器提供对会话数据的只读访问，包括条目列表、分支信息等。
     * 注意：此方法返回的会话管理器可能为 null，调用前应进行检查。
     *
     * @return 会话管理器实例
     */
    SessionManager getSessionManager();

    /**
     * 获取当前使用的模型。
     *
     * <p>返回当前会话中正在使用的 LLM 模型信息。
     *
     * @return 当前模型，如果未设置则返回 null
     */
    Model getModel();

    /**
     * 检查 Agent 是否处于空闲状态（不在流式输出中）。
     *
     * <p>当 Agent 正在生成回复时，返回 false。
     * 扩展可以使用此方法判断是否可以安全地执行操作。
     *
     * @return 如果空闲则返回 true
     */
    boolean isIdle();

    /**
     * 中止当前的 Agent 操作。
     *
     * <p>立即停止 Agent 正在进行的操作（如流式生成、工具执行等）。
     * 此操作不可逆。
     */
    void abort();

    /**
     * 检查是否有待处理的消息队列。
     *
     * <p>如果有消息在队列中等待处理，返回 true。
     *
     * @return 如果有待处理消息则返回 true
     */
    boolean hasPendingMessages();

    /**
     * 优雅地关闭并退出进程。
     *
     * <p>执行清理操作后退出程序。
     */
    void shutdown();

    /**
     * 获取当前活动模型的上下文使用情况。
     *
     * <p>返回当前上下文的 Token 使用量、上下文窗口大小和使用百分比。
     * 如果信息不可用，返回 null。
     *
     * @return 上下文使用情况，如果不可用则返回 null
     */
    ContextUsage getContextUsage();

    /**
     * 触发上下文压缩，不等待完成。
     *
     * <p>上下文压缩会汇总会话历史，减少 Token 使用量。
     * 此方法不阻塞，压缩在后台异步执行。
     */
    void compact();

    /**
     * 使用自定义选项触发上下文压缩。
     *
     * <p>允许指定自定义压缩指令、完成回调和错误回调。
     *
     * @param options 压缩选项，包含自定义指令和回调
     */
    void compact(CompactOptions options);

    /**
     * 获取当前生效的系统提示词。
     *
     * @return 系统提示词文本
     */
    String getSystemPrompt();

    /**
     * 检查 UI 是否可用。
     *
     * <p>在打印模式（print mode）或 RPC 模式下，UI 可能不可用。
     * 扩展可以根据此方法的结果决定是否执行 UI 相关的操作。
     *
     * @return 如果 UI 可用则返回 true（在打印 / RPC 模式下返回 false）
     */
    boolean hasUI();
}
