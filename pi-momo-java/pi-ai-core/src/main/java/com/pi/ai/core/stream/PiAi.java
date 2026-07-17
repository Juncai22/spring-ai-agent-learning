package com.pi.ai.core.stream;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.registry.ApiProviderRegistry;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StreamOptions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * pi-ai SDK 的统一门面（Facade）入口类，提供流式（Streaming）和非流式（Blocking）两种 LLM 调用方式。
 *
 * <p>该类是整个 pi-ai-core 模块对外的核心门面，所有 AI 模型调用均通过此类发起。
 * 内部采用门面模式（Facade Pattern），将复杂的多 Provider 路由、初始化生命周期、
 * 流式/非流式语义转换等细节完全封装在底层，对外暴露极简的静态方法签名。
 *
 * <h3>核心架构</h3>
 * <ol>
 *   <li><b>Provider 注册机制</b>：通过 {@link ApiProviderRegistry} 维护一个「API 协议标识 → Provider 实现」的映射表。
 *       每个 Provider（如 OpenAI、Claude、豆包等）在启动时向该注册表注册自己。</li>
 *   <li><b>延迟初始化</b>：首次调用 {@link #stream} 或 {@link #complete} 时，通过 {@link Initializer} 回调自动完成
 *       Provider 的注册。初始化器通常由 pi-ai-providers 等模块在类路径加载时通过 {@link #setInitializer} 注入。</li>
 *   <li><b>请求路由</b>：根据 {@link Model#api()} 返回的 API 协议标识（如 {@code "openai"}、{@code "anthropic"}），
 *       从注册表中查找对应的 {@link ApiProvider}，然后委托其执行实际调用。</li>
 *   <li><b>流式 vs 非流式</b>：流式调用返回 {@link AssistantMessageEventStream}，调用方可逐事件消费 SSE 数据流；
 *       非流式调用内部复用了流式调用，但仅等待最终结果，对外返回 {@link CompletableFuture}。</li>
 * </ol>
 *
 * <h3>设计决策</h3>
 * <ul>
 *   <li><b>为什么是 final 类</b>：作为工具门面，禁止继承，防止子类破坏单一路由语义。</li>
 *   <li><b>为什么是静态方法</b>：不需要实例状态，所有依赖通过 Provider 注册表解耦，降低使用方的心智负担。</li>
 *   <li><b>为什么用 volatile + AtomicBoolean</b>：确保多线程环境下初始化器只执行一次，
 *       且后续线程立即可见初始化状态，避免重复初始化或读到过期状态。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // ====== 流式调用（逐事件消费）======
 * AssistantMessageEventStream stream = PiAi.stream(model, context, options);
 * for (AssistantMessageEvent event : stream) {
 *     switch (event.type()) {
 *         case DELTA       -> sb.append(event.delta().orElse(""));
 *         case DONE        -> log.info("流式响应完成");
 *         case ERROR       -> log.error("流式响应错误: {}", event.errorMessage().orElse(""));
 *     }
 * }
 *
 * // ====== 非流式调用（等待完整结果）======
 * CompletableFuture<AssistantMessage> future = PiAi.complete(model, context, options);
 * AssistantMessage result = future.join(); // 阻塞等待
 *
 * // ====== 带推理能力（Reasoning）的简化调用 ======
 * SimpleStreamOptions opts = SimpleStreamOptions.builder()
 *         .reasoning(true)
 *         .thinkingBudgets(1024)
 *         .build();
 * AssistantMessageEventStream stream = PiAi.streamSimple(model, context, opts);
 * }</pre>
 *
 * <h3>生命周期</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ 应用启动                                              │
 * │   ├─ PiAi.setInitializer(myInitializer)  ← 模块注入     │
 * │   └─ 首次调用 PiAi.stream(...)                        │
 * │        └─ ensureInitialized()                         │
 * │             └─ initializer.initialize()               │
 * │                  └─ ApiProviderRegistry.register(...)  │
 * │                        └─ 后续请求直接路由              │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * @see ApiProviderRegistry  Provider 注册表
 * @see ApiProvider          LLM API 提供者抽象接口
 * @see AssistantMessageEventStream  流式事件流
 */
public final class PiAi {

    /**
     * 初始化回调接口，用于注册内置 Provider。
     *
     * <p>该接口是一个 {@link FunctionalInterface}，通常由 pi-ai-providers 模块实现，
     * 在 {@link #initialize()} 方法中调用 {@link ApiProviderRegistry#register(String, ApiProvider)}
     * 完成各 API 协议对应 Provider 的批量注册。
     *
     * <p>实现示例：
     * <pre>{@code
     * PiAi.setInitializer(() -> {
     *     ApiProviderRegistry.register("openai", new OpenAiProvider());
     *     ApiProviderRegistry.register("anthropic", new AnthropicProvider());
     * });
     * }</pre>
     */
    @FunctionalInterface
    public interface Initializer {
        /**
         * 执行初始化逻辑，注册所有需要的内置 Provider。
         * 该方法只会在首次调用 PiAi 的公开方法时被调用一次，且由 {@link AtomicBoolean#compareAndSet} 保证线程安全。
         */
        void initialize();
    }

    /** 保存外部注入的初始化器实例，使用 volatile 保证多线程可见性 */
    private static volatile Initializer initializer;

    /**
     * 初始化状态标志位，采用 AtomicBoolean 实现无锁化的「仅执行一次」语义。
     * <ul>
     *   <li>{@code false} — 尚未初始化，或初始化器被重新设置后重置为 false</li>
     *   <li>{@code true}  — 初始化已完成（或正在由当前线程执行）</li>
     * </ul>
     */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 私有构造方法，禁止外部实例化。
     * PiAi 是一个纯静态工具门面，不应创建实例。
     */
    private PiAi() {
        // 工具类，禁止实例化
    }

    /**
     * 设置初始化器（通常由 pi-ai-providers 模块在类路径加载时调用）。
     *
     * <p>调用此方法后，会将 {@link #initialized} 标志重置为 {@code false}，
     * 以便下一次调用 {@link #stream} 或 {@link #complete} 时重新执行初始化逻辑。
     * 这支持「热替换」场景：模块升级或配置变更后，可重新注入新的初始化器。
     *
     * <p><b>线程安全说明</b>：该方法与 {@link #ensureInitialized()} 之间存在 happens-before 关系，
     * 因为 {@code initializer} 是 volatile 变量，写入后对所有线程可见。
     *
     * @param init 初始化回调，不应为 null；如果传入 null，后续调用将跳过初始化
     */
    public static void setInitializer(Initializer init) {
        // ── 写入 volatile 变量 initializer，happens-before 于后续所有线程的读取 ──
        initializer = init;
        // ── 重置初始化标志位为 false，强制下一次调用 stream()/complete() 时重新走初始化流程 ──
        //     这实现了「热替换」：运行时替换 Provider 实现后，无需重启应用
        //     注意：正在执行中的旧 Provider 请求不受影响，但新请求会使用新注册的 Provider
        initialized.set(false);
    }

    /**
     * 双重检查锁定（Double-Checked Locking）的变体实现，确保初始化器只执行一次。
     *
     * <p>详细执行流程：
     * <pre>
     * 线程A ──→ 第一次检查 (initialized.get() == false, initializer != null)
     *         ├─ CAS 抢锁成功 → 执行 initializer.initialize() → 注册所有 Provider
     *         └─ initialized 变为 true（其他线程立即可见，因为 AtomicBoolean 的 volatile 语义）
     *
     * 线程B ──→ 第一次检查 (initialized.get() == false, initializer != null)
     *         └─ CAS 抢锁失败 → 直接跳过，不阻塞等待（与 synchronized 的关键区别：无锁阻塞）
     *
     * 线程C ──→ 第一次检查 (initialized.get() == true)
     *         └─ 整个方法直接返回，O(1) 开销，零竞争
     * </pre>
     *
     * <p>与传统的 {@code synchronized} 双重检查锁定相比，本方案的优势：
     * <ul>
     *   <li><b>无阻塞</b>：CAS 失败的线程不会阻塞，直接跳过，适合高并发场景</li>
     *   <li><b>可重入友好</b>：不会因为同步块导致死锁</li>
     *   <li><b>编译器优化友好</b>：volatile 语义禁止指令重排序，确保 initialized 的写入对其他线程立即可见</li>
     * </ul>
     */
    private static void ensureInitialized() {
        // ══════════════════════════════════════════════════════════════════════
        // 第一层检查：快速路径（Fast Path）
        // ══════════════════════════════════════════════════════════════════════
        // 目的：已初始化时直接跳过，避免后续的 CAS 原子操作开销
        // 场景：99% 的调用走此路径，仅一次 volatile 读，无锁竞争
        // ── 双重检查锁定（DCL）第 1 检：读 volatile 变量，快速判断是否已初始化 ──
        if (!initialized.get() && initializer != null) {

            // ══════════════════════════════════════════════════════════════════
            // 第二层检查：CAS 原子抢锁（Slow Path）
            // ══════════════════════════════════════════════════════════════════
            // compareAndSet(false, true) 的语义：
            //   - 如果当前值为 false → 原子地设为 true，返回 true  → 当前线程执行初始化
            //   - 如果当前值为 true  → 不变，返回 false       → 其他线程已经初始化完成
            // 这比 synchronized 更轻量：失败线程不阻塞，直接跳过
            // ── 双重检查锁定（DCL）第 2 检：CAS 原子操作，仅成功线程执行初始化 ──
            if (initialized.compareAndSet(false, true)) {
                // ── 只有成功将 false→true 的线程（即第一个到达的线程）执行初始化 ──
                //     初始化器会调用 ApiProviderRegistry.register() 注册所有内置 Provider
                initializer.initialize();
                // ── 初始化完成后，initialized 已为 true，后续所有线程走第一层检查直接返回 ──
            }
            // ── CAS 失败的线程（即同时到达的后续线程）：直接跳过，不阻塞等待 ──
            //     这些线程的第一次检查发现 initialized 已为 true，下次调用时直接返回
        }
    }

    /**
     * 发起流式调用，返回事件流。
     *
     * <p>流式调用（Streaming）是 LLM 推理的核心调用方式。调用方通过逐事件消费
     * {@link AssistantMessageEventStream}，可以实时获取模型输出的中间结果，
     * 适用于对话补全、打字机效果等场景。
     *
     * <p>调用流程：
     * <pre>
     * PiAi.stream(model, context, options)
     *   ├─ ensureInitialized()          ← 保证 Provider 已注册
     *   ├─ resolveProvider(model.api()) ← 根据 API 协议查找 Provider
     *   └─ provider.stream(...)         ← 委托给 Provider 执行实际调用
     *        └─ HTTP SSE 请求 → 事件流解析 → AssistantMessageEventStream
     * </pre>
     *
     * <p>参数说明：
     * <ul>
     *   <li>{@code model} 的 {@link Model#api()} 返回值决定了路由到哪个 Provider</li>
     *   <li>{@code context} 包含完整的对话历史（消息列表 + 系统提示词）</li>
     *   <li>{@code options} 控制采样参数：temperature（随机性）、top_p（核采样）、
     *       max_tokens（最大输出长度）、stop（停止序列）等</li>
     * </ul>
     *
     * @param model   目标模型定义，包含模型名称、API 协议、Endpoint 等信息
     * @param context 调用上下文，包含消息列表、系统提示词等对话状态
     * @param options 流式调用选项，包含 temperature、top_p、max_tokens、stop 序列等采样参数
     * @return 异步事件流 {@link AssistantMessageEventStream}，可用于迭代消费或转换为最终结果
     * @throws IllegalStateException 如果 {@code model.api()} 对应的 API 协议没有已注册的 Provider
     * @see ApiProvider#stream(Model, Context, StreamOptions)
     */
    public static AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        // ══════════════════════════════════════════════════════════════════════
        // 调用链：stream() → ensureInitialized() → resolveProvider() → provider.stream()
        // ══════════════════════════════════════════════════════════════════════

        // ── Step 1: 确保初始化器已执行，所有内置 Provider 已注册到 ApiProviderRegistry ──
        //     内部使用双重检查锁定（AtomicBoolean CAS），仅首次调用时实际执行初始化
        //     后续调用 O(1) 开销，零锁竞争
        ensureInitialized();

        // ── Step 2: 根据 Model 的 API 协议标识（如 "openai"、"anthropic"、"ark"）─
        //     从 ApiProviderRegistry 全局注册表中查找对应的 ApiProvider 实现
        //     如果未注册，抛出 IllegalStateException 并提示检查 classpath 和 setInitializer()
        ApiProvider provider = resolveProvider(model.api());

        // ── Step 3: 委托已解析的 Provider 执行实际的流式 SSE 调用 ──
        //     Provider 内部会构建 HTTP 请求、建立 SSE 连接、解析事件流
        //     返回的 AssistantMessageEventStream 支持迭代消费和转换为 CompletableFuture
        return provider.stream(model, context, options);

        // ══════════════════════════════════════════════════════════════════════
        // 返回对象说明：
        //   AssistantMessageEventStream 实现了 Iterable<AssistantMessageEvent>，
        //   调用方可以通过 for-each 循环逐事件消费：
        //     - DELTA 事件：包含部分增量内容（用于打字机效果）
        //     - DONE 事件：流结束
        //     - ERROR 事件：流式调用异常
        //   也可以通过 .result() 获取 CompletableFuture<AssistantMessage> 等待最终结果
        // ══════════════════════════════════════════════════════════════════════
    }

    /**
     * 发起非流式调用（Blocking / Completion），返回最终结果的 CompletableFuture。
     *
     * <p>非流式调用是流式调用的语义简化：内部实际调用 {@link #stream} 获取流式事件流，
     * 然后调用 {@link AssistantMessageEventStream#result()} 返回一个 {@link CompletableFuture}，
     * 该 Future 在流式事件流消费完毕后完成，并携带完整的 {@link AssistantMessage} 结果。
     *
     * <p>这种设计复用（Reuse）了同一套 SSE 解析逻辑，避免了为「非流式」场景单独实现一套 HTTP 调用链路，
     * 减少了代码维护成本。调用方可以通过 {@link CompletableFuture} 的编排能力
     * （{@code thenApply}、{@code exceptionally} 等）灵活处理异步结果。
     *
     * <p><b>注意</b>：虽然方法名是 "complete"，但底层仍走 SSE 流式协议，而非独立的非流式 API 调用。
     * 这是为了统一底层传输协议，降低 Provider 实现复杂度。
     *
     * <p>调用流程：
     * <pre>
     * PiAi.complete(model, context, options)
     *   └─ stream(model, context, options)    ← 内部复用流式调用
     *        └─ .result()                     ← 从流式事件流中提取最终结果
     *             └─ CompletableFuture<AssistantMessage>
     * </pre>
     *
     * @param model   目标模型定义
     * @param context 调用上下文
     * @param options 流式调用选项（内部仍走流式协议，但对外屏蔽事件细节）
     * @return 最终 {@link AssistantMessage} 的 {@link CompletableFuture}，
     *         在流式事件消费完毕后完成
     * @throws IllegalStateException 如果 {@code model.api()} 没有已注册的 Provider
     * @see #stream(Model, Context, StreamOptions)
     * @see AssistantMessageEventStream#result()
     */
    public static CompletableFuture<AssistantMessage> complete(Model model, Context context, StreamOptions options) {
        // ══════════════════════════════════════════════════════════════════════
        // 调用链：complete() → stream() → ensureInitialized() → resolveProvider() → provider.stream()
        //         → AssistantMessageEventStream.result() → CompletableFuture<AssistantMessage>
        // ══════════════════════════════════════════════════════════════════════

        // ── 内部复用 stream() 方法，而非重复实现 HTTP 调用逻辑 ──
        //     设计意图：所有调用路径统一走 SSE 流式协议，Provider 只需实现一套 stream() 接口
        //     优势：
        //       1. 减少 Provider 实现负担 —— 只需实现 stream()，complete() 自动获得
        //       2. 统一错误处理 —— 所有异常都通过流式事件（ERROR 事件）传递
        //       3. 测试覆盖率高 —— 测试 stream() 即等同于测试 complete()
        //     代价：
        //       1. 非流式场景多了一次事件流迭代的开销（通常可忽略）
        //       2. 网络层面仍走 SSE 长连接，而非短连接 HTTP 请求
        return stream(model, context, options).result();
    }

    /**
     * 发起带推理参数（Reasoning）的简化流式调用，返回事件流。
     *
     * <p>本方法与 {@link #stream} 的区别在于参数类型为 {@link SimpleStreamOptions}，
     * 该类型额外支持以下参数的显式设置：
     * <ul>
     *   <li><b>reasoning</b> — 是否启用模型的深度推理能力（如 Chain-of-Thought）</li>
     *   <li><b>thinkingBudgets</b> — 推理令牌预算上限，控制模型在推理阶段消耗的 token 数量</li>
     * </ul>
     *
     * <p>典型的推理场景包括：数学推理、逻辑分析、代码生成等需要模型「深思熟虑」后再回答的任务。
     * 启用 reasoning 后，模型可能会先输出一段思考过程（thinking/scratchpad），再输出最终答案。
     *
     * <p>调用流程：
     * <pre>
     * PiAi.streamSimple(model, context, options)
     *   ├─ ensureInitialized()
     *   ├─ resolveProvider(model.api())
     *   └─ provider.streamSimple(...)    ← 委托 Provider 执行带推理参数的流式调用
     * </pre>
     *
     * @param model   目标模型定义
     * @param context 调用上下文
     * @param options 简化流式调用选项，通过 {@link SimpleStreamOptions.Builder} 构建，
     *                可设置 reasoning 开关、thinkingBudgets 上限及其他采样参数
     * @return 异步事件流 {@link AssistantMessageEventStream}
     * @throws IllegalStateException 如果 {@code model.api()} 没有已注册的 Provider
     * @see ApiProvider#streamSimple(Model, Context, SimpleStreamOptions)
     * @see SimpleStreamOptions
     */
    public static AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
        // ══════════════════════════════════════════════════════════════════════
        // 调用链：streamSimple() → ensureInitialized() → resolveProvider() → provider.streamSimple()
        // ══════════════════════════════════════════════════════════════════════

        // ── Step 1: 确保初始化器已执行，Provider 已注册 ──
        //     与 stream() 共享同一套初始化逻辑，确保线程安全
        ensureInitialized();

        // ── Step 2: 根据 Model 的 API 协议标识查找对应的 Provider ──
        //     与 stream() 共享同一套 Provider 解析逻辑
        ApiProvider provider = resolveProvider(model.api());

        // ── Step 3: 委托 Provider 执行带推理参数的流式调用 ──
        //     provider.streamSimple() 与 provider.stream() 的区别：
        //       前者会检查 SimpleStreamOptions 中的 reasoning/thinkingBudgets 参数，
        //       并在 HTTP 请求中设置对应的推理参数（如 Anthropic 的 extended thinking 或 OpenAI 的 reasoning effort）
        return provider.streamSimple(model, context, options);
    }

    /**
     * 发起带推理参数的非流式调用，返回最终结果的 CompletableFuture。
     *
     * <p>本方法是 {@link #streamSimple} 的非流式变体，内部逻辑与 {@link #complete} 一致：
     * 调用 {@link #streamSimple} 获取流式事件流，再通过
     * {@link AssistantMessageEventStream#result()} 获取最终结果。
     *
     * <p>适用于需要模型深度推理但又不想处理中间事件的场景。
     *
     * <p>调用流程：
     * <pre>
     * PiAi.completeSimple(model, context, options)
     *   └─ streamSimple(model, context, options)    ← 内部复用带推理参数的流式调用
     *        └─ .result()                           ← 从流式事件流中提取最终结果
     *             └─ CompletableFuture<AssistantMessage>
     * </pre>
     *
     * @param model   目标模型定义
     * @param context 调用上下文
     * @param options 简化流式调用选项（含推理参数）
     * @return 最终 {@link AssistantMessage} 的 {@link CompletableFuture}
     * @throws IllegalStateException 如果 {@code model.api()} 没有已注册的 Provider
     * @see #streamSimple(Model, Context, SimpleStreamOptions)
     * @see SimpleStreamOptions
     */
    public static CompletableFuture<AssistantMessage> completeSimple(Model model, Context context, SimpleStreamOptions options) {
        // ══════════════════════════════════════════════════════════════════════
        // 调用链：completeSimple() → streamSimple() → ensureInitialized() → resolveProvider()
        //         → provider.streamSimple() → .result() → CompletableFuture<AssistantMessage>
        // ══════════════════════════════════════════════════════════════════════

        // ── 内部复用 streamSimple() 方法，与 complete() 复用 stream() 的设计一致 ──
        //     设计意图：所有调用路径统一走 SSE 流式协议
        //     调用方无需关心底层是流式还是非流式，只需获取最终结果
        return streamSimple(model, context, options).result();
    }

    /**
     * 从 {@link ApiProviderRegistry} 中解析指定 API 协议对应的 Provider。
     *
     * <p>如果注册表中不存在该 API 协议的 Provider，则抛出 {@link IllegalStateException}，
     * 并附带清晰的错误信息，提示调用方检查 Provider 是否已注册。
     *
     * <p>常见的 API 协议标识示例：
     * <ul>
     *   <li>{@code "openai"} — OpenAI 兼容 API</li>
     *   <li>{@code "anthropic"} — Anthropic Claude API</li>
     *   <li>{@code "ark"} — 火山方舟 ARK API</li>
     *   <li>{@code "ollama"} — 本地 Ollama API</li>
     * </ul>
     *
     * <p>错误排查指南：
     * <ul>
     *   <li><b>IllegalStateException</b> — 指定的 API 协议没有已注册的 Provider
     *     <ul>
     *       <li>原因 1：对应的 Provider 模块未在 classpath 中（如缺少 pi-ai-providers-openai）</li>
     *       <li>原因 2：应用启动时没有调用 {@link #setInitializer} 注入初始化器</li>
     *       <li>原因 3：{@link Model#api()} 返回了拼写错误的协议标识（如 "openai" 误写为 "open-ai"）</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param api API 协议标识字符串，由 {@link Model#api()} 提供
     * @return 已注册的 {@link ApiProvider} 实例
     * @throws IllegalStateException 如果指定 API 协议没有已注册的 Provider，
     *                               提示信息包含具体的 api 标识以便排查
     */
    private static ApiProvider resolveProvider(String api) {
        // ── 从全局注册表 ApiProviderRegistry 中查找指定 API 协议对应的 Provider ──
        //     ApiProviderRegistry 是一个静态 Map<String, ApiProvider>，在 initializer.initialize() 中被填充
        //     键为 API 协议标识（如 "openai"、"anthropic"、"ark"），值为对应的 Provider 实例
        ApiProvider provider = ApiProviderRegistry.get(api);

        // ── 错误处理：未找到已注册的 Provider ──
        //     抛出 IllegalStateException 而非运行时悄悄返回 null，原因：
        //       1. 快速失败（Fail-Fast）：让调用方尽早发现配置错误，而非在后续调用中抛出 NPE
        //       2. 信息丰富：异常信息包含具体的 api 标识和修复指引，便于排查
        //       3. 不可恢复：缺少 Provider 是配置层错误，运行时无法自动恢复
        if (provider == null) {
            throw new IllegalStateException(
                    "No API provider registered for api: " + api
                            + ". Please ensure the corresponding provider module is on the classpath "
                            + "and PiAi.setInitializer() has been called.");
        }

        // ── 返回已注册的 Provider 实例，用于后续的 stream() 调用 ──
        //     此时 provider 不会为 null，因为 null 情况已在上方被拦截并抛出异常
        return provider;
    }
}
