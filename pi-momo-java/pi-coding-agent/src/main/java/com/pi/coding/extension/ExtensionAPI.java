package com.pi.coding.extension;

import com.pi.agent.types.AgentTool;
import com.pi.ai.core.types.ImageContent;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ThinkingLevel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 扩展 API 接口 —— 提供给扩展工厂函数 (ExtensionFactory) 的核心编程接口。
 *
 * <p>扩展通过此 API 与 Agent 运行时交互，实现插件系统的全部功能。
 * 该接口是扩展开发的主要入口点，定义了扩展可以执行的所有操作。
 *
 * <p>扩展可以使用此 API 完成以下操作：
 * <ul>
 *   <li><b>注册工具</b>（要求 5.3）：注册可供 LLM 调用的工具，包括名称、描述、参数模式和执行函数</li>
 *   <li><b>注册命令</b>（要求 5.4）：注册自定义斜杠命令，用户可在输入框中以 "/" 前缀触发</li>
 *   <li><b>注册快捷键</b>（要求 5.5）：注册键盘快捷键，用户可通过按键组合触发操作</li>
 *   <li><b>注册 CLI 标志位</b>（要求 5.6）：注册 CLI 标志位，通过命令行参数控制扩展行为</li>
 *   <li><b>订阅事件</b>（要求 5.8）：订阅 Agent 生命周期事件，如会话开始、切换、Agent 循环等</li>
 *   <li><b>发送消息</b>（要求 5.9）：向 Agent 发送文本消息或带图片的富消息</li>
 *   <li><b>会话操作</b>（要求 5.10）：向会话追加自定义条目，持久化扩展状态</li>
 *   <li><b>工具管理</b>（要求 5.11）：获取和设置当前激活的工具列表</li>
 *   <li><b>模型管理</b>（要求 5.12）：获取和设置当前使用的模型及思考级别</li>
 *   <li><b>提供者注册</b>（要求 5.13）：注册或覆盖模型提供者，包括 OAuth 支持</li>
 *   <li><b>事件总线</b>（要求 5.14）：获取扩展间通信的事件总线</li>
 * </ul>
 *
 * <p><b>验证要求：Requirements 5.3-5.14</b>
 */
public interface ExtensionAPI {

    // ══════════════════════════════════════════════════════════════════════════
    // 工具注册 (要求 5.3)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册一个可供 LLM 调用的工具。
     *
     * <p>工具是扩展向 LLM 暴露能力的主要方式。LLM 可以在对话中根据上下文自主决定调用已注册的工具。
     * 每个工具需要一个唯一的名称、描述、JSON Schema 参数定义和执行函数。
     * 工具执行时可流式更新结果，支持取消信号。
     *
     * @param tool 工具定义，包含名称、描述、参数模式和执行器
     */
    void registerTool(ToolDefinition tool);

    /**
     * 注销一个先前注册的工具。
     *
     * <p>移除后，该工具将不再出现在 LLM 可用的工具列表中，
     * LLM 将无法再调用此工具。
     *
     * @param name 要注销的工具名称
     */
    void unregisterTool(String name);

    // ══════════════════════════════════════════════════════════════════════════
    // 命令注册 (要求 5.4)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册一个自定义斜杠命令。
     *
     * <p>斜杠命令是用户可在输入框中以 "/" 前缀触发的快捷操作。
     * 例如注册名为 "status" 的命令后，用户输入 "/status" 即可执行。
     * 命令可提供参数自动补全功能，提升用户体验。
     *
     * @param command 命令定义，包含名称、描述、参数补全函数和执行处理器
     */
    void registerCommand(CommandDefinition command);

    // ══════════════════════════════════════════════════════════════════════════
    // 快捷键注册 (要求 5.5)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册一个键盘快捷键。
     *
     * <p>快捷键允许用户通过键盘组合键快速触发扩展操作，
     * 例如 "ctrl+k" 或 "f1"。快捷键处理器接收扩展上下文并异步执行。
     *
     * @param shortcut 快捷键定义，包含键名、描述和执行处理器
     */
    void registerShortcut(ShortcutDefinition shortcut);

    // ══════════════════════════════════════════════════════════════════════════
    // CLI 标志位注册 (要求 5.6)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册一个 CLI 标志位。
     *
     * <p>CLI 标志位允许通过命令行参数控制扩展的配置和行为。
     * 支持布尔类型和字符串类型的标志位，可设置默认值。
     * 注册后可通过 {@link #getFlag} 方法获取标志位的运行时值。
     *
     * @param flag 标志位定义，包含名称、描述、类型和默认值
     */
    void registerFlag(FlagDefinition flag);

    /**
     * 获取已注册 CLI 标志位的当前值。
     *
     * <p>如果标志位未被注册，返回 null。标志位的值可能来源于命令行参数注入
     * 或默认值。如果未通过命令行提供值且未设置默认值，返回 null。
     *
     * @param name 标志位名称
     * @return 标志位的当前值，如果未注册则返回 null
     */
    Object getFlag(String name);

    // ══════════════════════════════════════════════════════════════════════════
    // 事件订阅 (要求 5.8)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 订阅一个扩展事件，通过事件类类型指定。
     *
     * <p>扩展可以订阅各类 Agent 生命周期事件，如会话事件（start/switch/fork/compact/shutdown）、
     * Agent 循环事件（start/end/turn）、消息事件（start/update/end）、工具执行事件等。
     * 事件处理器可返回结果来修改 Agent 行为（如拦截工具调用、修改上下文消息等）。
     * 返回的 Runnable 可用于取消订阅。
     *
     * @param eventType 要订阅的事件类，如 {@code ExtensionEvent.SessionStartEvent.class}
     * @param handler   事件处理器，接收事件对象和扩展上下文
     * @param <T>       事件类型参数
     * @return 一个 Runnable，调用后取消订阅
     */
    <T extends ExtensionEvent> Runnable on(Class<T> eventType, ExtensionEventHandler<T> handler);

    /**
     * 订阅一个扩展事件，通过事件类型名称指定。
     *
     * <p>事件类型名称采用 snake_case 格式，例如 "session_start"、"agent_start"、"tool_call" 等。
     * 此方法适用于需要动态订阅事件名称的场景（如事件名称来自配置文件）。
     *
     * @param eventType 事件类型名称（如 "session_start"）
     * @param handler   事件处理器
     * @return 一个 Runnable，调用后取消订阅
     */
    Runnable on(String eventType, ExtensionEventHandler<? extends ExtensionEvent> handler);

    // ══════════════════════════════════════════════════════════════════════════
    // 消息发送 (要求 5.9)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 向 Agent 发送一条纯文本消息。
     *
     * <p>发送的消息将被注入到当前会话中，Agent 会像处理用户输入一样处理它。
     * 这允许扩展以编程方式向 Agent 提供信息或触发操作。
     * 注意：在扩展加载期间调用此方法会抛出 UnsupportedOperationException。
     *
     * @param text 消息文本内容
     */
    void sendMessage(String text);

    /**
     * 向 Agent 发送一条带图片的富文本消息。
     *
     * <p>图片作为 {@link ImageContent} 列表附加到消息中，Agent 能够"看到"这些图片
     * 并进行多模态理解。适用于扩展需要向 Agent 提供视觉信息的场景。
     *
     * @param text   消息文本内容
     * @param images 附加的图片内容列表
     */
    void sendMessage(String text, List<ImageContent> images);

    /**
     * 向会话发送一条自定义类型的消息。
     *
     * <p>自定义消息允许扩展定义自己的消息类型和结构，用于扩展间通信或自定义 UI 渲染。
     * 可通过 {@link SendMessageOptions} 控制是否触发新的 Agent 轮次以及消息的投递方式。
     *
     * @param customType 自定义类型标识符，用于区分不同类型的自定义消息
     * @param content    消息内容，可以是任意类型
     * @param display    是否在 UI 中显示
     * @param details    附加详情信息（可为 null）
     * @param options    发送选项，控制是否触发新轮次和投递方式（可为 null）
     * @param <T>        详情类型参数
     */
    <T> void sendCustomMessage(String customType, Object content, boolean display, T details, SendMessageOptions options);

    // ══════════════════════════════════════════════════════════════════════════
    // 会话操作 (要求 5.10)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 向会话追加一条自定义条目，用于持久化扩展状态（不发送给 LLM）。
     *
     * <p>自定义条目存储在会话文件中，但不会作为上下文发送给 LLM。
     * 这使得扩展可以保存自己的内部状态，在会话恢复时重新加载。
     * 适用于需要跨会话保持状态的场景，如配置信息、缓存数据等。
     *
     * @param customType 自定义类型标识符，用于区分不同类型的自定义条目
     * @param data       条目数据，将被序列化存储（可为 null）
     * @param <T>        数据类型参数
     */
    <T> void appendCustomEntry(String customType, T data);

    /**
     * 向会话追加一条自定义消息条目，该条目会参与 LLM 上下文。
     *
     * <p>与 {@link #appendCustomEntry} 不同，此方法添加的条目会作为消息的一部分
     * 发送给 LLM，因此可以影响 LLM 的推理和行为。但也可以在 UI 中控制是否显示。
     *
     * @param customType 自定义类型标识符
     * @param content    消息内容
     * @param display    是否在 UI 中显示
     * @param details    附加详情信息（可为 null）
     * @param <T>        详情类型参数
     */
    <T> void appendCustomMessage(String customType, Object content, boolean display, T details);

    // ══════════════════════════════════════════════════════════════════════════
    // 工具管理 (要求 5.11)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前激活的工具名称列表。
     *
     * <p>只有激活的工具才会出现在 LLM 可用的工具列表中。
     * 扩展可以通过此方法了解当前哪些工具可用。
     *
     * @return 当前激活的工具名称列表
     */
    List<String> getActiveToolNames();

    /**
     * 设置当前激活的工具列表。
     *
     * <p>通过提供工具名称列表来控制哪些工具对 LLM 可见。
     * 此方法可用于动态切换工具集，例如根据当前上下文启用或禁用特定工具。
     *
     * @param toolNames 要激活的工具名称列表
     */
    void setActiveToolNames(List<String> toolNames);

    /**
     * 获取所有已配置的工具信息，包括名称和描述。
     *
     * <p>返回的工具信息包含名称、描述和参数模式，但不包含执行函数。
     * 适用于 UI 展示或调试目的。
     *
     * @return 所有工具的信息列表
     */
    List<ToolInfo> getAllTools();

    // ══════════════════════════════════════════════════════════════════════════
    // 模型管理 (要求 5.12)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前使用的模型。
     *
     * <p>返回当前会话中正在使用的 LLM 模型。如果尚未设置模型，返回 null。
     *
     * @return 当前模型，如果未设置则返回 null
     */
    Model getModel();

    /**
     * 设置当前使用的模型。
     *
     * <p>切换模型到指定的新模型。如果新模型所需的 API Key 不可用，
     * 切换可能失败，返回 false。此方法是异步的，返回的 CompletableFuture
     * 在切换完成后完成。
     *
     * @param model 要设置的新模型
     * @return 一个 CompletableFuture，成功时返回 true，API Key 不可用时返回 false
     */
    CompletableFuture<Boolean> setModel(Model model);

    /**
     * 获取当前的思考级别（Thinking Level）。
     *
     * <p>思考级别控制 LLM 在生成回复时的推理深度，影响响应质量和速度。
     *
     * @return 当前的思考级别
     */
    ThinkingLevel getThinkingLevel();

    /**
     * 设置思考级别，会自动限制在模型能力范围内。
     *
     * <p>某些模型可能不支持较高的思考级别，设置时会被自动限制到模型支持的最大级别。
     *
     * @param level 要设置的思考级别
     */
    void setThinkingLevel(ThinkingLevel level);

    // ══════════════════════════════════════════════════════════════════════════
    // 提供者注册 (要求 5.13)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册或覆盖一个模型提供者。
     *
     * <p>提供者（Provider）是模型服务的抽象，代表一个 API 端点服务商。
     * 根据配置内容的不同，此方法的行为也不同：
     * <ul>
     *   <li>如果提供了 {@code models}：替换该提供者的所有现有模型</li>
     *   <li>如果只提供 {@code baseUrl}：覆盖现有模型的 API 端点 URL</li>
     *   <li>如果提供了 {@code oauth}：注册 OAuth 提供者，支持 /login 登录流程</li>
     * </ul>
     *
     * <p>在扩展初始加载期间，此调用会被排队，待 Runner 绑定上下文后统一应用。
     * 加载完成后调用则立即生效。
     *
     * @param name   提供者名称，如 "anthropic"、"openai"
     * @param config 提供者配置，包含 API 端点、密钥、模型列表和 OAuth 配置
     */
    void registerProvider(String name, ProviderConfig config);

    /**
     * 注销一个先前注册的提供者。
     *
     * <p>移除属于该提供者的所有模型，并恢复被该提供者覆盖的内置模型。
     * 适用于扩展需要动态切换或移除提供者的场景。
     *
     * @param name 要注销的提供者名称
     */
    void unregisterProvider(String name);

    // ══════════════════════════════════════════════════════════════════════════
    // 事件总线 (要求 5.14)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取扩展间通信的共享事件总线。
     *
     * <p>事件总线（EventBus）允许不同扩展之间通过命名事件进行松耦合通信。
     * 一个扩展可以发射事件，其他扩展可以订阅该事件并做出响应。
     * 这种机制避免了扩展之间的直接依赖关系。
     *
     * @return 事件总线实例
     */
    EventBus getEventBus();

    // ══════════════════════════════════════════════════════════════════════════
    // 会话元数据
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 设置会话的显示名称（显示在会话选择器中）。
     *
     * <p>自定义会话名称可以帮助用户在多会话场景中快速识别不同会话的用途。
     *
     * @param name 会话名称
     */
    void setSessionName(String name);

    /**
     * 获取当前会话的名称。
     *
     * @return 会话名称，如果未设置则返回 null
     */
    String getSessionName();

    /**
     * 设置或清除某条会话条目的标签。
     *
     * <p>标签可以用于标记和分类会话中的条目，便于后续检索和管理。
     * 传入 null 表示清除标签。
     *
     * @param entryId 条目标识符
     * @param label   要设置的标签，传入 null 则清除已有标签
     */
    void setLabel(String entryId, String label);

    // ══════════════════════════════════════════════════════════════════════════
    // 命令
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前会话中可用的斜杠命令。
     *
     * <p>返回所有可用命令的列表，包括内置命令和扩展注册的命令。
     * 可用于 UI 展示或在编程中检查命令是否可用。
     *
     * @return 命令信息列表
     */
    List<CommandInfo> getCommands();
}
