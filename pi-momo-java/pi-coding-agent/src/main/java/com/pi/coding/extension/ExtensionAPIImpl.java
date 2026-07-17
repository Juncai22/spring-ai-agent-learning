package com.pi.coding.extension;

import com.pi.ai.core.types.ImageContent;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ThinkingLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ExtensionAPI 的实现类，在扩展加载期间使用。
 *
 * <p>该实现类负责收集扩展工厂函数通过 ExtensionAPI 接口注册的所有组件，
 * 并在加载完成后调用 {@link #buildExtension()} 构建一个不可变的 {@link Extension} 记录。
 *
 * <p>设计特点：
 * <ul>
 *   <li>注册收集模式：所有注册信息先收集到内部映射中，最后统一构建为 Extension 记录</li>
 *   <li>延迟应用：提供者注册被延迟排队，待 Runner 绑定上下文后统一应用</li>
 *   <li>桩实现：运行时的功能（如发送消息、会话操作等）在加载期间不可用，抛出 UnsupportedOperationException</li>
 *   <li>CamelCase 到 snake_case 转换：事件类名自动转换为事件类型名称</li>
 * </ul>
 *
 * <p>包级私有，仅限 {@link ExtensionRunner} 内部使用。
 */
class ExtensionAPIImpl implements ExtensionAPI {

    /** ExtensionRunner 运行器引用，用于获取事件总线等运行时资源 */
    private final ExtensionRunner runner;
    /** 按事件类型名称分组的事件处理器映射 */
    private final Map<String, List<ExtensionEventHandler<?>>> handlers = new HashMap<>();
    /** 按工具名称索引的已注册工具映射 */
    private final Map<String, RegisteredTool> tools = new HashMap<>();
    /** 按命令名称索引的已注册命令映射 */
    private final Map<String, RegisteredCommand> commands = new HashMap<>();
    /** 按快捷键键名索引的已注册快捷键映射 */
    private final Map<String, RegisteredShortcut> shortcuts = new HashMap<>();
    /** 按标志位名称索引的已注册 CLI 标志位映射 */
    private final Map<String, RegisteredFlag> flags = new HashMap<>();
    /** 标志位的运行时值映射 */
    private final Map<String, Object> flagValues = new HashMap<>();
    /** 扩展销毁时的清理回调 */
    private Runnable disposeHandler;
    /** 扩展路径，标识该扩展的来源，默认为 "<inline>" 表示内联扩展 */
    private String extensionPath = "<inline>";

    /**
     * 构造 ExtensionAPIImpl 实例。
     *
     * @param runner ExtensionRunner 运行器引用
     */
    ExtensionAPIImpl(ExtensionRunner runner) {
        this.runner = runner;
    }

    /**
     * 设置扩展路径。
     *
     * @param path 扩展路径（如 JAR 文件路径）
     */
    void setExtensionPath(String path) {
        this.extensionPath = path;
    }

    /**
     * 构建不可变的 Extension 记录。
     *
     * <p>将所有收集到的注册信息封装为不可变映射，并创建 Extension 记录。
     * 调用此方法后，应停止对注册信息的修改。
     *
     * @return 构建好的 Extension 记录
     */
    Extension buildExtension() {
        return new Extension(
            extensionPath,
            Map.copyOf(handlers),
            Map.copyOf(tools),
            Map.copyOf(commands),
            Map.copyOf(shortcuts),
            Map.copyOf(flags),
            disposeHandler
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具注册
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void registerTool(ToolDefinition tool) {
        // 将工具定义和扩展路径一起封装为 RegisteredTool，按名称索引
        tools.put(tool.name(), new RegisteredTool(tool, extensionPath));
    }

    @Override
    public void unregisterTool(String name) {
        // 从映射中移除指定名称的工具
        tools.remove(name);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 命令注册
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void registerCommand(CommandDefinition command) {
        // 将命令定义和扩展路径一起封装为 RegisteredCommand，按名称索引
        commands.put(command.name(), new RegisteredCommand(command, extensionPath));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 快捷键注册
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void registerShortcut(ShortcutDefinition shortcut) {
        // 将快捷键定义和扩展路径一起封装为 RegisteredShortcut，按键名索引
        shortcuts.put(shortcut.key(), new RegisteredShortcut(shortcut, extensionPath));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 标志位注册
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void registerFlag(FlagDefinition flag) {
        // 将标志位定义和扩展路径一起封装为 RegisteredFlag，按名称索引
        flags.put(flag.name(), new RegisteredFlag(flag, extensionPath));
        // 如果标志位有默认值，则同时保存到 flagValues 映射中
        if (flag.defaultValue() != null) {
            flagValues.put(flag.name(), flag.defaultValue());
        }
    }

    @Override
    public Object getFlag(String name) {
        // 如果标志位未注册，返回 null
        if (!flags.containsKey(name)) {
            return null;
        }
        // 返回标志位的运行时值，可能是默认值或通过命令行注入的值
        return flagValues.get(name);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 事件订阅
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public <T extends ExtensionEvent> Runnable on(Class<T> eventType, ExtensionEventHandler<T> handler) {
        // 通过事件类自动推导事件类型名称，然后委托给字符串类型名称的重载方法
        String typeName = getEventTypeName(eventType);
        return on(typeName, handler);
    }

    @Override
    public Runnable on(String eventType, ExtensionEventHandler<? extends ExtensionEvent> handler) {
        // 获取或创建该事件类型的处理器列表，添加处理器，返回取消订阅的 Runnable
        List<ExtensionEventHandler<?>> eventHandlers = handlers.computeIfAbsent(eventType, k -> new ArrayList<>());
        eventHandlers.add(handler);
        return () -> eventHandlers.remove(handler);
    }

    /**
     * 将事件类的简单名称（CamelCase）转换为事件类型名称（snake_case）。
     *
     * <p>转换规则：
     * <ol>
     *   <li>将 CamelCase 转换为 snake_case，每个大写字母前加下划线</li>
     *   <li>全部转换为小写字母</li>
     *   <li>移除末尾的 "_event" 后缀（如果存在）</li>
     * </ol>
     *
     * <p>例如：{@code SessionStartEvent} -> {@code session_start}
     *
     * @param eventType 事件类
     * @return 事件类型名称，如 "session_start"
     */
    private String getEventTypeName(Class<? extends ExtensionEvent> eventType) {
        // 获取类简单名称
        String simpleName = eventType.getSimpleName();
        // 将 CamelCase 转换为 snake_case
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        // 如果名称以 "Event" 结尾，移除它（注意：移除的是 snake_case 后 "_event" 后缀）
        String name = result.toString();
        if (name.endsWith("_event")) {
            name = name.substring(0, name.length() - 6);
        }
        return name;
    }


    // ══════════════════════════════════════════════════════════════════════════
    // 消息发送（桩实现 —— 将在运行时连接）
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void sendMessage(String text) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("sendMessage 在扩展加载期间不可用");
    }

    @Override
    public void sendMessage(String text, List<ImageContent> images) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("sendMessage 在扩展加载期间不可用");
    }

    @Override
    public <T> void sendCustomMessage(String customType, Object content, boolean display, T details, SendMessageOptions options) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("sendCustomMessage 在扩展加载期间不可用");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 会话操作（桩实现）
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public <T> void appendCustomEntry(String customType, T data) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("appendCustomEntry 在扩展加载期间不可用");
    }

    @Override
    public <T> void appendCustomMessage(String customType, Object content, boolean display, T details) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("appendCustomMessage 在扩展加载期间不可用");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具管理（桩实现）
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public List<String> getActiveToolNames() {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("getActiveToolNames 在扩展加载期间不可用");
    }

    @Override
    public void setActiveToolNames(List<String> toolNames) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("setActiveToolNames 在扩展加载期间不可用");
    }

    @Override
    public List<ToolInfo> getAllTools() {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("getAllTools 在扩展加载期间不可用");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 模型管理（桩实现）
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public Model getModel() {
        // TODO: 连接到运行时上下文
        return null;
    }

    @Override
    public CompletableFuture<Boolean> setModel(Model model) {
        // TODO: 连接到运行时上下文
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("setModel 在扩展加载期间不可用"));
    }

    @Override
    public ThinkingLevel getThinkingLevel() {
        // TODO: 连接到运行时上下文
        return ThinkingLevel.MINIMAL;
    }

    @Override
    public void setThinkingLevel(ThinkingLevel level) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("setThinkingLevel 在扩展加载期间不可用");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 提供者注册（加载期间排队，加载完成后统一应用）
    // ══════════════════════════════════════════════════════════════════════════

    /** 待处理的提供者注册队列，在扩展加载期间收集，之后由 Runner 统一应用 */
    private final List<ProviderRegistration> pendingProviderRegistrations = new ArrayList<>();
    /** 待处理的提供者注销队列 */
    private final List<String> pendingProviderUnregistrations = new ArrayList<>();

    @Override
    public void registerProvider(String name, ProviderConfig config) {
        // 将提供者注册请求加入队列，不在加载期间直接执行
        pendingProviderRegistrations.add(new ProviderRegistration(name, config));
    }

    @Override
    public void unregisterProvider(String name) {
        // 将提供者注销请求加入队列
        pendingProviderUnregistrations.add(name);
    }

    /**
     * 获取待处理的提供者注册列表。
     *
     * @return 待处理的提供者注册列表
     */
    List<ProviderRegistration> getPendingProviderRegistrations() {
        return pendingProviderRegistrations;
    }

    /**
     * 获取待处理的提供者注销列表。
     *
     * @return 待处理的提供者名称列表
     */
    List<String> getPendingProviderUnregistrations() {
        return pendingProviderUnregistrations;
    }

    /**
     * 提供者注册的内部记录，保存提供者名称和配置。
     */
    record ProviderRegistration(String name, ProviderConfig config) { }

    // ══════════════════════════════════════════════════════════════════════════
    // 事件总线
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public EventBus getEventBus() {
        // 直接委托给 ExtensionRunner 的共享事件总线实例
        return runner.getEventBus();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 会话元数据（桩实现）
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void setSessionName(String name) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("setSessionName 在扩展加载期间不可用");
    }

    @Override
    public String getSessionName() {
        // TODO: 连接到运行时上下文
        return null;
    }

    @Override
    public void setLabel(String entryId, String label) {
        // TODO: 连接到运行时上下文
        throw new UnsupportedOperationException("setLabel 在扩展加载期间不可用");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 命令
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public List<CommandInfo> getCommands() {
        // TODO: 连接到运行时上下文
        return List.of();
    }
}
