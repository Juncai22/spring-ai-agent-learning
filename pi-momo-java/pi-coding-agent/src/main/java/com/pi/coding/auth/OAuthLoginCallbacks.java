package com.pi.coding.auth;

import java.util.concurrent.CompletableFuture;

/**
 * OAuth 登录流程回调接口，定义了登录过程中各阶段的回调方法。
 *
 * <p>在 OAuth 登录流程中，需要与用户交互的操作（如打开浏览器、监听回调等）
 * 通过此接口抽象出来，由调用方实现具体的交互逻辑。
 *
 * <p>主要回调阶段：
 * <ol>
 *   <li>{@link #openAuthUrl(String)} - 打开授权 URL，通常在浏览器中打开</li>
 *   <li>{@link #receiveAuthCode()} - 等待并接收用户授权后的回调授权码</li>
 *   <li>{@link #onSuccess(OAuthCredential)} - 登录成功通知（可选实现）</li>
 *   <li>{@link #onError(Throwable)} - 登录失败通知（可选实现）</li>
 * </ol>
 *
 * <p>onSuccess 和 onError 方法提供了默认的空实现（default method），
 * 调用方可以根据需要选择性地覆盖它们。
 *
 * @see AuthStorage#login(String, OAuthLoginCallbacks)
 */
public interface OAuthLoginCallbacks {
    
    /**
     * 打开授权 URL 供用户进行授权操作。
     *
     * <p>通常实现为在操作系统的默认浏览器中打开该 URL，让用户登录并授权应用。
     * 返回的 CompletableFuture 应在浏览器成功打开后完成，
     * 如果打开失败（如无可用浏览器）则应异常完成。
     *
     * @param authUrl 需要打开的授权 URL
     * @return 一个 CompletableFuture，在 URL 成功打开后完成
     */
    CompletableFuture<Void> openAuthUrl(String authUrl);
    
    /**
     * 接收用户授权后回调回来的授权码。
     *
     * <p>用户授权后，OAuth 提供商会将浏览器重定向到预配置的回调 URL，
     * 并在 URL 中携带授权码（authorization code）。此方法需要监听并捕获该授权码。
     *
     * <p>典型实现方式：
     * <ul>
     *   <li>启动本地 HTTP 服务器监听回调端口的请求</li>
     *   <li>从 URL 参数中提取授权码</li>
     *   <li>返回提取到的授权码</li>
     * </ul>
     *
     * @return 一个 CompletableFuture，异步返回从回调中获取的授权码字符串
     */
    CompletableFuture<String> receiveAuthCode();
    
    /**
     * OAuth 登录成功时的回调通知。
     *
     * <p>当授权码成功交换为访问令牌，且凭证已保存到存储后，此方法被调用。
     * 默认实现为空操作，调用方可以覆盖此方法以执行登录成功后的后续操作，
     * 例如更新 UI 状态、记录日志等。
     *
     * @param credential 登录成功后获取的 OAuth 凭证，包含访问令牌和刷新令牌
     */
    default void onSuccess(OAuthCredential credential) {}
    
    /**
     * OAuth 登录失败时的回调通知。
     *
     * <p>当登录流程中的任何步骤（如打开 URL、接收授权码、交换令牌）失败时，
     * 此方法被调用。默认实现为空操作，调用方可以覆盖此方法以处理登录失败的情况，
     * 例如向用户显示错误信息、记录错误日志等。
     *
     * @param error 登录过程中发生的异常或错误信息
     */
    default void onError(Throwable error) {}
}
