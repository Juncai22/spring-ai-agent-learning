package com.pi.coding.extension;

import com.pi.coding.session.SessionManager;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 扩展命令上下文接口 —— 命令处理器的扩展上下文，提供会话控制方法。
 *
 * <p>此接口继承自 {@link ExtensionContext}，增加了仅在用户发起的命令中安全的
 * 会话控制方法。这些方法允许命令处理程序执行高级会话操作，如：
 * <ul>
 *   <li>等待 Agent 完成流式输出</li>
 *   <li>创建新会话、切换会话</li>
 *   <li>从指定条目分叉（fork）创建新会话</li>
 *   <li>在会话树中导航</li>
 *   <li>重新加载扩展和技能</li>
 * </ul>
 *
 * <p>注意：这些操作具有"破坏性"，因此仅应在用户主动触发的命令中可用，
 * 而不应在自动触发的事件处理器中使用。
 */
public interface ExtensionCommandContext extends ExtensionContext {

    /**
     * 等待 Agent 完成流式输出。
     *
     * <p>阻塞直到 Agent 完成当前正在进行的流式输出并进入空闲状态。
     * 适用于需要在 Agent 响应完成后执行操作的场景。
     *
     * @return 一个 CompletableFuture，在 Agent 空闲时完成
     */
    CompletableFuture<Void> waitForIdle();

    /**
     * 创建一个新会话。
     *
     * <p>使用默认选项创建新会话。新会话将替换当前会话。
     *
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> newSession();

    /**
     * 使用指定选项创建一个新会话。
     *
     * <p>允许指定父会话路径和初始化设置函数，提供更灵活的新建会话控制。
     *
     * @param options 新会话选项，包含父会话路径和设置函数
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> newSession(NewSessionOptions options);

    /**
     * 从指定条目分叉（fork），创建一个新的会话文件。
     *
     * <p>分叉操作会从当前会话树的指定条目创建一个新的分支会话文件，
     * 保留该条目之前的所有上下文。
     *
     * @param entryId 要分叉的条目标识符
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> fork(String entryId);

    /**
     * 导航到会话树中的不同位置。
     *
     * <p>将会话切换到指定目标条目的位置，可能触发会话上下文压缩。
     *
     * @param targetId 目标条目标识符
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> navigateTree(String targetId);

    /**
     * 使用指定选项导航到会话树中的不同位置。
     *
     * <p>允许自定义导航行为，如是否生成摘要、自定义摘要指令等。
     *
     * @param targetId 目标条目标识符
     * @param options  导航选项，包含摘要生成设置
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> navigateTree(String targetId, NavigateTreeOptions options);

    /**
     * 切换到不同的会话文件。
     *
     * <p>加载指定路径的会话文件，替换当前会话。
     *
     * @param sessionPath 会话文件路径
     * @return 一个 CompletableFuture，完成时包含会话操作结果
     */
    CompletableFuture<SessionOperationResult> switchSession(String sessionPath);

    /**
     * 重新加载扩展、技能、提示词和主题。
     *
     * <p>热重载所有可动态加载的资源，使更改立即生效。
     *
     * @return 一个 CompletableFuture，在重载完成后完成
     */
    CompletableFuture<Void> reload();

    /**
     * 会话操作的结果。
     *
     * @param cancelled 操作是否被取消
     */
    record SessionOperationResult(boolean cancelled) { }

    /**
     * 创建新会话的选项。
     *
     * @param parentSession 父会话路径（可为 null）
     * @param setup         要在会话管理器上运行的设置函数（可为 null）
     */
    record NewSessionOptions(
        String parentSession,
        Function<SessionManager, CompletableFuture<Void>> setup
    ) { }

    /**
     * 会话树导航的选项。
     *
     * @param summarize           是否生成摘要
     * @param customInstructions  自定义摘要指令
     * @param replaceInstructions 是否替换默认指令
     * @param label               分支摘要条目的标签
     */
    record NavigateTreeOptions(
        Boolean summarize,
        String customInstructions,
        Boolean replaceInstructions,
        String label
    ) { }
}
