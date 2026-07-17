package com.pi.coding.extension;

/**
 * OAuth 登录流程的回调接口 —— 提供用户交互所需的操作。
 *
 * <p>在 OAuth 登录流程中，系统需要与用户交互来完成认证。
 * 此回调接口定义了三种交互方式：
 * <ul>
 *   <li>打开浏览器 URL：引导用户到认证页面</li>
 *   <li>显示消息：向用户展示提示信息</li>
 *   <li>提示输入：向用户请求输入（如授权码、PIN 码等）</li>
 * </ul>
 */
public interface OAuthLoginCallbacks {

    /**
     * 在用户的浏览器中打开一个 URL。
     *
     * <p>用于跳转到 OAuth 提供者的授权页面，让用户进行身份验证和授权。
     *
     * @param url 要打开的 URL
     */
    void openUrl(String url);

    /**
     * 向用户显示一条消息。
     *
     * <p>用于在登录过程中展示提示信息，如"请检查浏览器中的授权页面"等。
     *
     * @param message 要显示的消息
     */
    void showMessage(String message);

    /**
     * 提示用户输入信息。
     *
     * <p>用于获取用户输入的授权码、PIN 码或其他必需信息。
     *
     * @param prompt 提示消息
     * @return 用户的输入，如果用户取消则返回 null
     */
    String promptInput(String prompt);
}
