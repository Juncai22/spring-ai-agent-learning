package com.pi.coding.session;

/**
 * 创建新会话的选项。
 *
 * <p>控制新会话的创建参数，包括自定义会话 ID 和父会话引用。
 * 父会话用于分叉会话场景，当从现有会话分支创建新会话时使用。
 *
 * <p>验证需求：1.2
 *
 * @param id            可选的自定义会话 ID（null 时自动生成 UUID）
 * @param parentSession 可选的父会话文件路径（用于分叉的会话）
 */
public record NewSessionOptions(
        String id,
        String parentSession
) {
    /**
     * 创建默认选项（自动生成 ID，无父会话）。
     *
     * @return 默认选项
     */
    public static NewSessionOptions defaults() {
        return new NewSessionOptions(null, null);
    }

    /**
     * 创建指定父会话的选项。
     *
     * @param parentSession 父会话文件路径
     * @return 包含父会话的选项
     */
    public static NewSessionOptions withParent(String parentSession) {
        return new NewSessionOptions(null, parentSession);
    }

    /**
     * 创建指定会话 ID 的选项。
     *
     * @param id 会话 ID
     * @return 包含自定义 ID 的选项
     */
    public static NewSessionOptions withId(String id) {
        return new NewSessionOptions(id, null);
    }
}