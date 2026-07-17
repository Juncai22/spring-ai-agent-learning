package com.pi.ai.core.types;

/**
 * 请求载荷拦截器回调接口。
 * 函数式接口，用于在发送 API 请求前拦截和修改请求载荷。
 *
 * <p>在发送 API 请求前，允许检查或替换 Provider 载荷。
 * 返回 {@code null} 表示保持载荷不变。
 *
 * <p>对应 TypeScript 中的 {@code onPayload} 回调：
 * {@code (payload: unknown, model: Model) => unknown | undefined}
 *
 * <p>使用场景：在请求发送前添加自定义字段、修改参数、注入认证信息等。
 */
// Step 1: 使用 @FunctionalInterface 注解标记为函数式接口
// 原因：允许使用 Lambda 表达式实现，简化调用方代码
@FunctionalInterface
public interface PayloadInterceptor {

    /**
     * 拦截并可选地替换请求载荷。
     *
     * @param payload 原始请求载荷，由框架生成（包含 temperature、messages 等）
     * @param model   目标模型，包含 Provider 和模型配置信息
     * @return 替换后的载荷，或 {@code null} 表示保持不变
     */
    // 接口方法：接收原始载荷和模型信息，返回修改后的载荷
    // 参数 payload 是框架生成的请求体，包含所有标准参数
    // 参数 model 是目标模型的配置，可用于获取 Provider 特定信息
    // 返回值：如果返回非 null 对象，框架将使用该对象替换原始载荷发送
    //         如果返回 null，框架保持原始载荷不变
    Object intercept(Object payload, Model model);
}