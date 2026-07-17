package com.pi.ai.core.registry;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StreamOptions;

/**
 * API Provider 接口，定义了大语言模型（LLM）服务提供商的核心抽象。
 *
 * <p>每个 LLM 服务提供商（如 Anthropic、OpenAI 等）需实现此接口，
 * 以接入到 pi-ai-core 的流式调用框架中。
 *
 * <p>接口提供两种流式调用方式：
 * <ul>
 *   <li>{@link #stream(Model, Context, StreamOptions)}：基础流式调用，支持完整的流式选项配置</li>
 *   <li>{@link #streamSimple(Model, Context, SimpleStreamOptions)}：简化流式调用，封装了推理参数（reasoning/thinkingBudgets）</li>
 * </ul>
 *
 * <p>实现类需通过 {@link ApiProviderRegistry#register(ApiProvider)} 注册到全局注册表，
 * 注册时需提供 {@link #api()} 返回的协议标识作为唯一键。
 *
 * <p>设计参考：与 TypeScript 版本的 ApiProvider 接口保持一致，确保跨语言架构统一。
 *
 * @see ApiProviderRegistry
 * @see AssistantMessageEventStream
 * @see StreamOptions
 * @see SimpleStreamOptions
 */
public interface ApiProvider {

    /**
     * 返回此 Provider 支持的 API 协议标识。
     *
     * <p>该标识作为全局注册表中的唯一键，用于按协议路由到对应的 Provider 实现。
     * 常见取值示例：
     * <ul>
     *   <li>{@code "anthropic-messages"} — Anthropic Messages API</li>
     *   <li>{@code "openai-completions"} — OpenAI Completions API</li>
     *   <li>{@code "openai-chat"} — OpenAI Chat Completions API</li>
     * </ul>
     *
     * <p>实现类应返回一个不可变的、与 Provider 实现唯一对应的字符串常量，
     * 不应在运行时动态变化。
     *
     * @return API 协议标识字符串，不可为 null
     */
    // 【协议标识】返回此 Provider 对应的 API 协议名称，例如 "anthropic-messages"
    // 【注册表键】该返回值将作为 ApiProviderRegistry 中 ConcurrentHashMap 的 key
    // 【不可变性】必须返回常量，运行时不可变更，否则已注册的条目将无法通过 get() 正确查找
    String api();

    /**
     * 发起流式调用，返回异步事件流。
     *
     * <p>消费者通过迭代 {@link AssistantMessageEventStream} 逐个获取事件，
     * 支持处理内容块（content delta）、工具调用（tool call）、
     * 流结束（stream end）和错误事件。
     *
     * <p>此方法为完整的流式调用入口，支持所有 {@link StreamOptions} 配置项，
     * 包括但不限于：温度、top-p、最大输出 token、停止序列、流式模式等。
     *
     * @param model   目标模型定义，包含模型 ID、provider、定价等信息
     * @param context 调用上下文，包含系统提示（system prompt）、
     *                消息历史列表（message list）、工具定义列表（tool list）
     * @param options 流式调用选项，提供完整的参数配置能力
     * @return 异步事件流，消费者可通过 {@link AssistantMessageEventStream#iterator()} 逐个获取事件
     */
    // 【流式调用入口】发起 LLM 调用，以事件流（EventStream）形式返回结果
    // 【参数 model】目标模型定义，包含模型 ID、provider、定价等信息
    // 【参数 context】调用上下文，包含 system prompt、message list、tool list
    // 【参数 options】流式调用选项，提供完整的参数配置能力
    // 【返回值】异步事件流，消费者可通过 iterator() 逐个获取事件
    AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);

    /**
     * 发起带推理参数的简化流式调用，返回异步事件流。
     *
     * <p>与 {@link #stream(Model, Context, StreamOptions)} 的区别在于，
     * 此方法使用 {@link SimpleStreamOptions} 封装了推理相关的参数：
     * <ul>
     *   <li>{@code reasoning} — 推理级别（如 none/low/medium/high/xhigh）</li>
     *   <li>{@code thinkingBudgets} — 思考预算 token 数</li>
     * </ul>
     *
     * <p>适用于需要控制模型推理深度但无需其他复杂配置的场景。
     *
     * @param model   目标模型定义
     * @param context 调用上下文
     * @param options 简化流式调用选项，专注推理参数配置
     * @return 异步事件流
     */
    // 【简化流式调用】与 stream() 的区别在于使用 SimpleStreamOptions 封装了推理参数
    // 【适用场景】需要控制模型推理深度（reasoning level）但无需其他复杂配置时使用
    // 【参数 options】简化流式调用选项，专注推理参数配置（推理级别、思考预算）
    AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options);
}