package com.pi.agent.config;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;

/**
 * 可替换的流式调用函数类型，用于执行 LLM 调用。
 *
 * <p>该接口的函数签名与 {@code PiAi.streamSimple} 方法匹配：
 * 接收 {@link Model}、{@link Context} 和 {@link SimpleStreamOptions} 参数，
 * 返回 {@link AssistantMessageEventStream} 事件流。
 *
 * <p>当未配置此函数时，Agent 主循环默认使用 {@code PiAi.streamSimple} 实现。
 * 通过提供自定义实现，可以实现以下功能：
 * <ul>
 *   <li>流式调用拦截：在调用前后添加自定义逻辑</li>
 *   <li>调用监控：记录每次调用的耗时、token 消耗等指标</li>
 *   <li>故障注入测试：模拟 LLM 调用的各种异常场景</li>
 *   <li>多模型路由：根据请求内容动态选择不同的模型或提供商</li>
 *   <li>调用缓存：对相同请求进行缓存以提高响应速度</li>
 * </ul>
 *
 * <p>实现必须保证不抛出异常；失败应通过返回的事件流中的 error 事件
 * 和 {@code stopReason} 为 {@code error} 或 {@code aborted} 来表达。
 *
 * <p><b>验证的需求：12.1</b>
 *
 * @see com.pi.ai.core.PiAi#streamSimple
 */
@FunctionalInterface
public interface StreamFn {

    /**
     * 执行 LLM 流式调用。
     * <p>此方法封装了实际的 LLM API 调用，返回一个事件流用于接收
     * 模型生成的文本、工具调用等异步事件。
     *
     * @param model   目标 LLM 模型，指定使用哪个模型进行推理
     * @param context 对话上下文，包含系统提示词（system prompt）、消息历史、可用工具等
     * @param options 流式调用选项，如 temperature、maxTokens、reasoning 等参数
     * @return 助理消息事件流，用于流式接收模型输出的文本片段和工具调用事件
     */
    AssistantMessageEventStream stream(Model model, Context context, SimpleStreamOptions options);
}
