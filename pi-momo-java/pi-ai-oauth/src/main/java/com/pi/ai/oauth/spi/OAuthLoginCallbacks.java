package com.pi.ai.oauth.spi;

import java.util.concurrent.CompletableFuture;

/**
 * OAuth 登录流程的回调接口，用于 Provider 与用户交互层之间的通信。
 *
 * <p>在 OAuth 登录过程中，Provider 需要通过多种方式与用户交互，例如：
 * <ul>
 *   <li>通知用户打开浏览器访问认证 URL</li>
 *   <li>提示用户输入设备码或授权码</li>
 *   <li>显示进度消息，反馈当前登录状态</li>
 *   <li>在无法自动接收回调时，请求用户手动输入授权码</li>
 * </ul>
 *
 * <p>该接口抽象了这些交互行为，使 Provider 实现不依赖于具体的 UI 层（命令行、GUI 等）。
 * 调用方（如 CLI 或 GUI 应用）实现此接口，将用户交互逻辑注入到 OAuth 流程中。
 *
 * <p>对应 pi-mono 前端的 OAuthLoginCallbacks 接口。
 */
public interface OAuthLoginCallbacks {

    /**
     * 认证信息回调，通知用户需要执行的操作。
     * <p>通常用于指示用户打开浏览器访问指定的 URL 以完成 OAuth 授权，例如：
     * <ul>
     *   <li>授权码流程：通知用户浏览器已打开，请确认授权</li>
     *   <li>设备码流程：显示设备码和验证 URL，要求用户在浏览器中输入</li>
     * </ul>
     *
     * @param info 包含认证 URL 和操作说明的认证信息，不可为 null
     */
    void onAuth(OAuthAuthInfo info);

    /**
     * 提示用户输入信息的回调，异步等待用户输入。
     * <p>用于需要用户手动输入的场景，例如：
     * <ul>
     *   <li>输入设备码进行设备绑定</li>
     *   <li>输入自定义的访问令牌</li>
     *   <li>确认或选择额外的配置选项</li>
     * </ul>
     * <p>返回的 {@link CompletableFuture} 在用户完成输入后完成，支持异步等待。
     *
     * @param prompt 包含提示信息、占位符和是否允许空输入的提示对象，不可为 null
     * @return 用户输入的内容，通过 CompletableFuture 异步返回
     */
    CompletableFuture<String> onPrompt(OAuthPrompt prompt);

    /**
     * 进度消息回调，用于向用户反馈当前登录流程的进展状态。
     * <p>可选实现，默认不执行任何操作。可在登录过程中多次调用以更新状态信息，
     * 例如："正在启动浏览器..."、"等待用户授权..."、"正在交换令牌..." 等。
     *
     * @param message 进度消息字符串，不可为 null
     */
    default void onProgress(String message) {}

    /**
     * 手动输入授权码的回调，用于无法自动接收回调的场景。
     * <p>当 {@link OAuthProviderInterface#usesCallbackServer()} 返回 {@code false} 时，
     * 登录流程可能通过此回调请求用户手动输入授权码，以完成授权码流程。
     * <p>默认实现返回 {@code null}，表示不支持手动输入方式。
     *
     * @return 用户输入的授权码，通过 CompletableFuture 异步返回；如果返回 {@code null} 表示无输入
     */
    default CompletableFuture<String> onManualCodeInput() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 认证信息记录，封装 OAuth 认证过程中需要展示给用户的信息。
     *
     * @param url          用户需要访问的认证 URL，用于在浏览器中打开
     * @param instructions 操作说明文字，指导用户如何完成认证
     */
    record OAuthAuthInfo(String url, String instructions) {}

    /**
     * 提示信息记录，封装需要用户输入时的提示内容。
     *
     * @param message     提示用户的消息文本
     * @param placeholder 输入框的占位符文本（可选，用于 UI 展示）
     * @param allowEmpty  是否允许用户输入空值
     */
    record OAuthPrompt(String message, String placeholder, boolean allowEmpty) {
        /**
         * 简化构造器，仅传入提示消息，占位符为 null，不允许空输入。
         *
         * @param message 提示用户的消息文本
         */
        public OAuthPrompt(String message) {
            this(message, null, false);
        }
    }
}