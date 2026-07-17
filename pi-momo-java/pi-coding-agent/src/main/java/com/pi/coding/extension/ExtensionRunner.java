package com.pi.coding.extension;

import com.pi.agent.types.AgentMessage;
import com.pi.ai.core.types.ContentBlock;
import com.pi.ai.core.types.ImageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 扩展运行器 —— 执行扩展并管理其生命周期。
 *
 * <p>ExtensionRunner 是扩展系统的核心运行时组件，负责以下职责：
 * <ul>
 *   <li><b>扩展加载</b>（要求 5.15）：从工厂函数加载扩展，注册所有组件</li>
 *   <li><b>事件分发</b>（要求 5.16）：将事件发射给所有已注册的处理器，按注册顺序调用</li>
 *   <li><b>特殊事件处理</b>（要求 5.17-5.20）：处理上下文、工具调用/结果、输入等拦截事件</li>
 *   <li><b>错误处理</b>（要求 5.21）：捕获处理器异常，确保一个处理器不会影响其他处理器</li>
 *   <li><b>生命周期管理</b>：管理扩展的创建、运行和销毁</li>
 * </ul>
 *
 * <p>线程安全：使用 {@link CopyOnWriteArrayList} 和 {@link ConcurrentHashMap} 确保并发安全性。
 * 事件处理在异步线程中执行，使用 {@link CompletableFuture} 管理异步结果。
 *
 * <p><b>验证要求：Requirements 5.15-5.21</b>
 */
public class ExtensionRunner {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionRunner.class);

    /** 已加载的扩展列表，使用 CopyOnWriteArrayList 确保线程安全 */
    private final List<Extension> extensions = new CopyOnWriteArrayList<>();
    /** 按事件类型名称分组的事件处理器映射，使用 ConcurrentHashMap 确保线程安全 */
    private final Map<String, List<ExtensionEventHandler<?>>> handlers = new ConcurrentHashMap<>();
    /** 错误监听器列表，用于接收扩展运行时的错误通知 */
    private final List<Consumer<ExtensionError>> errorListeners = new CopyOnWriteArrayList<>();
    /** 扩展间通信的事件总线实现 */
    private final EventBusImpl eventBus = new EventBusImpl();

    /** 是否已释放的标志位，用于防止释放后的操作 */
    private volatile boolean disposed = false;

    /**
     * 从工厂函数加载扩展。
     *
     * <p>对每个工厂函数执行以下步骤：
     * <ol>
     *   <li>创建 {@link ExtensionAPIImpl} 实例</li>
     *   <li>调用工厂的 {@code create} 方法，让工厂注册组件</li>
     *   <li>调用 {@link ExtensionAPIImpl#buildExtension()} 构建不可变的 Extension 记录</li>
     *   <li>将扩展的事件处理器注册到全局处理器映射中</li>
     * </ol>
     *
     * <p>如果某个工厂加载失败，不会影响其他工厂的加载，但错误信息会被收集。
     *
     * <p><b>验证要求：Requirement 5.15</b>
     *
     * @param factories 扩展工厂函数列表
     * @return 加载结果，包含已加载的扩展列表和加载过程中的错误
     */
    public LoadExtensionsResult loadExtensions(List<ExtensionFactory> factories) {
        List<Extension> loadedExtensions = new ArrayList<>();
        List<LoadExtensionsResult.LoadError> errors = new ArrayList<>();

        for (ExtensionFactory factory : factories) {
            try {
                ExtensionAPIImpl api = new ExtensionAPIImpl(this);
                factory.create(api);
                Extension extension = api.buildExtension();
                loadedExtensions.add(extension);
                extensions.add(extension);

                // 将扩展中注册的事件处理器注册到全局处理器映射中
                for (Map.Entry<String, List<ExtensionEventHandler<?>>> entry : extension.handlers().entrySet()) {
                    handlers.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>())
                            .addAll(entry.getValue());
                }
            } catch (Exception e) {
                logger.error("从工厂加载扩展失败", e);
                errors.add(new LoadExtensionsResult.LoadError(
                    factory.getClass().getName(),
                    e.getMessage()
                ));
            }
        }

        return new LoadExtensionsResult(loadedExtensions, errors);
    }

    /**
     * 向所有已注册的处理器发射事件。
     *
     * <p>处理器按注册顺序依次调用。如果某个处理器抛出异常，
     * 错误会被记录并跳过该处理器，继续调用下一个处理器。
     * 所有处理器在同一异步任务中串行执行，可以确保事件处理的顺序性。
     *
     * <p><b>验证要求：Requirement 5.16</b>
     *
     * @param event 要发射的事件
     * @param <T>   事件类型
     * @return 一个 CompletableFuture，在所有处理器执行完成后完成
     */
    public <T extends ExtensionEvent> CompletableFuture<Void> emit(T event) {
        if (disposed) {
            return CompletableFuture.completedFuture(null);
        }

        String eventType = event.type();
        List<ExtensionEventHandler<?>> eventHandlers = handlers.get(eventType);

        if (eventHandlers == null || eventHandlers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            ExtensionContext context = createContext();
            for (ExtensionEventHandler<?> handler : eventHandlers) {
                try {
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<T> typedHandler = (ExtensionEventHandler<T>) handler;
                    CompletableFuture<Object> result = typedHandler.handle(event, context);
                    if (result != null) {
                        result.join(); // 等待处理器完成
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError(eventType, e.getMessage(), e));
                }
            }
        });
    }

    /**
     * 检查是否有针对指定事件类型的已注册处理器。
     *
     * @param eventType 事件类型名称
     * @return 如果有已注册的处理器则返回 true
     */
    public boolean hasHandlers(String eventType) {
        List<ExtensionEventHandler<?>> eventHandlers = handlers.get(eventType);
        return eventHandlers != null && !eventHandlers.isEmpty();
    }


    // ══════════════════════════════════════════════════════════════════════════
    // 特殊事件处理（要求 5.17-5.20）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 发射上下文事件并收集修改后的消息列表。
     *
     * <p>这是"context"类型事件的特殊处理方法。处理器可以修改传入的消息列表，
     * 每个处理器接收到的是前一个处理器修改后的消息列表（第一个处理器收到原始消息列表）。
     * 处理器通过返回 {@link EventResult.ContextEventResult} 来提供修改后的消息列表。
     *
     * <p>如果没有任何处理器注册，则直接返回原始消息列表。
     *
     * <p><b>验证要求：Requirement 5.19</b>
     *
     * @param messages 原始消息列表
     * @return 一个 CompletableFuture，完成时包含可能被修改后的消息列表
     */
    public CompletableFuture<List<AgentMessage>> emitContext(List<AgentMessage> messages) {
        if (disposed || !hasHandlers("context")) {
            return CompletableFuture.completedFuture(messages);
        }

        return CompletableFuture.supplyAsync(() -> {
            List<AgentMessage> currentMessages = new ArrayList<>(messages);
            ExtensionContext context = createContext();

            for (ExtensionEventHandler<?> handler : handlers.getOrDefault("context", List.of())) {
                try {
                    ExtensionEvent.ContextEvent event = new ExtensionEvent.ContextEvent(currentMessages);
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<ExtensionEvent.ContextEvent> typedHandler =
                        (ExtensionEventHandler<ExtensionEvent.ContextEvent>) handler;
                    CompletableFuture<Object> result = typedHandler.handle(event, context);
                    if (result != null) {
                        Object handlerResult = result.join();
                        if (handlerResult instanceof EventResult.ContextEventResult contextResult) {
                            if (contextResult.messages() != null) {
                                currentMessages = contextResult.messages();
                            }
                        }
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError("context", e.getMessage(), e));
                }
            }

            return currentMessages;
        });
    }

    /**
     * 发射 before_provider_request 事件并收集修改后的请求载荷。
     *
     * <p>此事件在向 LLM 提供者发送 API 请求之前触发。处理器可以替换请求载荷，
     * 实现请求拦截和修改功能。每个处理器接收到的是前一个处理器修改后的载荷。
     * 处理器通过返回修改后的载荷对象来提供替换值。
     *
     * <p><b>验证要求：Requirement 5.20</b>
     *
     * @param payload 原始请求载荷
     * @return 一个 CompletableFuture，完成时包含可能被修改后的请求载荷
     */
    public CompletableFuture<Object> emitBeforeProviderRequest(Object payload) {
        if (disposed || !hasHandlers("before_provider_request")) {
            return CompletableFuture.completedFuture(payload);
        }

        return CompletableFuture.supplyAsync(() -> {
            Object currentPayload = payload;
            ExtensionContext context = createContext();

            for (ExtensionEventHandler<?> handler : handlers.getOrDefault("before_provider_request", List.of())) {
                try {
                    ExtensionEvent.BeforeProviderRequestEvent event =
                        new ExtensionEvent.BeforeProviderRequestEvent(currentPayload);
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<ExtensionEvent.BeforeProviderRequestEvent> typedHandler =
                        (ExtensionEventHandler<ExtensionEvent.BeforeProviderRequestEvent>) handler;
                    CompletableFuture<Object> result = typedHandler.handle(event, context);
                    if (result != null) {
                        Object handlerResult = result.join();
                        if (handlerResult != null) {
                            currentPayload = handlerResult;
                        }
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError("before_provider_request", e.getMessage(), e));
                }
            }

            return currentPayload;
        });
    }

    /**
     * 发射输入事件并收集处理结果。
     *
     * <p>此事件在用户输入被接收后、Agent 处理之前触发。处理器可以：
     * <ul>
     *   <li>转换输入文本和图片（Transform）</li>
     *   <li>指示输入已被完全处理，无需 Agent 进一步处理（Handled）</li>
     *   <li>继续使用原始输入（Continue）</li>
     * </ul>
     *
     * <p>如果任何处理器返回 "Handled"，则停止处理并立即返回该结果。
     *
     * @param text   输入文本
     * @param images 附加图片（可为 null）
     * @param source 输入来源（"interactive"、"rpc" 或 "extension"）
     * @return 输入事件处理结果
     */
    public CompletableFuture<EventResult.InputEventResult> emitInput(
            String text, List<ImageContent> images, String source) {
        if (disposed || !hasHandlers("input")) {
            return CompletableFuture.completedFuture(new EventResult.InputEventResult.Continue());
        }

        return CompletableFuture.supplyAsync(() -> {
            String currentText = text;
            List<ImageContent> currentImages = images;
            ExtensionContext context = createContext();

            for (ExtensionEventHandler<?> handler : handlers.getOrDefault("input", List.of())) {
                try {
                    ExtensionEvent.InputEvent event = new ExtensionEvent.InputEvent(currentText, currentImages, source);
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<ExtensionEvent.InputEvent> typedHandler =
                        (ExtensionEventHandler<ExtensionEvent.InputEvent>) handler;
                    CompletableFuture<Object> result = typedHandler.handle(event, context);
                    if (result != null) {
                        Object handlerResult = result.join();
                        if (handlerResult instanceof EventResult.InputEventResult inputResult) {
                            if (inputResult instanceof EventResult.InputEventResult.Handled) {
                                return inputResult;
                            } else if (inputResult instanceof EventResult.InputEventResult.Transform transform) {
                                currentText = transform.text();
                                if (transform.images() != null) {
                                    currentImages = transform.images();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError("input", e.getMessage(), e));
                }
            }

            if (!currentText.equals(text) || currentImages != images) {
                return new EventResult.InputEventResult.Transform(currentText, currentImages);
            }
            return new EventResult.InputEventResult.Continue();
        });
    }


    /**
     * 发射工具调用事件并检查该工具是否应被阻止执行。
     *
     * <p>在工具执行前触发。处理器可以检查工具调用的参数，并决定是否阻止该工具的执行。
     * 如果任何处理器返回的 {@link EventResult.ToolCallEventResult} 中 block 字段为 true，
     * 则立即短路返回，不再继续调用后续处理器，工具的执行将被阻止。
     *
     * <p><b>验证要求：Requirement 5.17</b>
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param input      工具输入参数
     * @return 工具调用事件结果，可能包含阻止执行的信息
     */
    public CompletableFuture<EventResult.ToolCallEventResult> emitToolCall(
            String toolCallId, String toolName, Object input) {
        if (disposed || !hasHandlers("tool_call")) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            ExtensionContext context = createContext();
            EventResult.ToolCallEventResult result = null;

            for (ExtensionEventHandler<?> handler : handlers.getOrDefault("tool_call", List.of())) {
                try {
                    ExtensionEvent.ToolCallEvent event = new ExtensionEvent.ToolCallEvent(toolCallId, toolName, input);
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<ExtensionEvent.ToolCallEvent> typedHandler =
                        (ExtensionEventHandler<ExtensionEvent.ToolCallEvent>) handler;
                    CompletableFuture<Object> handlerFuture = typedHandler.handle(event, context);
                    if (handlerFuture != null) {
                        Object handlerResult = handlerFuture.join();
                        if (handlerResult instanceof EventResult.ToolCallEventResult toolCallResult) {
                            result = toolCallResult;
                            if (Boolean.TRUE.equals(toolCallResult.block())) {
                                return result; // 如果工具被阻止，立即短路返回
                            }
                        }
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError("tool_call", e.getMessage(), e));
                }
            }

            return result;
        });
    }

    /**
     * 发射工具结果事件并收集修改后的结果。
     *
     * <p>在工具执行完成后触发。处理器可以修改工具执行的结果，包括：
     * <ul>
     *   <li>修改结果内容块（content）</li>
     *   <li>修改结果详情（details）</li>
     *   <li>修改错误状态（isError）</li>
     * </ul>
     *
     * <p>每个处理器可以修改结果的任意部分，未修改的部分保持原值。
     * 如果没有任何处理器修改结果，返回 null。
     *
     * <p><b>验证要求：Requirement 5.18</b>
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param input      工具输入参数
     * @param content    结果内容块
     * @param details    结果详情（可为 null）
     * @param isError    结果是否为错误
     * @return 工具结果事件结果，如果被修改过则包含修改后的值
     */
    public CompletableFuture<EventResult.ToolResultEventResult> emitToolResult(
            String toolCallId, String toolName, Object input,
            List<ContentBlock> content, Object details, boolean isError) {
        if (disposed || !hasHandlers("tool_result")) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            List<ContentBlock> currentContent = content;
            Object currentDetails = details;
            boolean currentIsError = isError;
            boolean modified = false;
            ExtensionContext context = createContext();

            for (ExtensionEventHandler<?> handler : handlers.getOrDefault("tool_result", List.of())) {
                try {
                    ExtensionEvent.ToolResultEvent event = new ExtensionEvent.ToolResultEvent(
                        toolCallId, toolName, input, currentContent, currentDetails, currentIsError
                    );
                    @SuppressWarnings("unchecked")
                    ExtensionEventHandler<ExtensionEvent.ToolResultEvent> typedHandler =
                        (ExtensionEventHandler<ExtensionEvent.ToolResultEvent>) handler;
                    CompletableFuture<Object> handlerFuture = typedHandler.handle(event, context);
                    if (handlerFuture != null) {
                        Object handlerResult = handlerFuture.join();
                        if (handlerResult instanceof EventResult.ToolResultEventResult toolResultResult) {
                            if (toolResultResult.content() != null) {
                                currentContent = toolResultResult.content();
                                modified = true;
                            }
                            if (toolResultResult.details() != null) {
                                currentDetails = toolResultResult.details();
                                modified = true;
                            }
                            if (toolResultResult.isError() != null) {
                                currentIsError = toolResultResult.isError();
                                modified = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    emitError(new ExtensionError("tool_result", e.getMessage(), e));
                }
            }

            if (!modified) {
                return null;
            }

            return new EventResult.ToolResultEventResult(currentContent, currentDetails, currentIsError);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 错误处理（要求 5.21）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 注册一个错误监听器。
     *
     * <p>错误监听器会在扩展事件处理器抛出异常时被调用。
     * 返回的 Runnable 可用于取消注册。
     *
     * @param listener 错误监听器，接收扩展错误信息
     * @return 一个 Runnable，调用后取消注册
     */
    public Runnable onError(Consumer<ExtensionError> listener) {
        errorListeners.add(listener);
        return () -> errorListeners.remove(listener);
    }

    /**
     * 向所有已注册的监听器发射错误事件。
     *
     * <p>记录错误日志，然后逐个通知所有错误监听器。
     * 监听器中的异常会被捕获并记录，确保一个监听器不会影响其他监听器。
     *
     * @param error 扩展错误信息
     */
    private void emitError(ExtensionError error) {
        logger.warn("扩展事件 '{}' 中发生错误: {}", error.event(), error.message(), error.cause());
        for (Consumer<ExtensionError> listener : errorListeners) {
            try {
                listener.accept(error);
            } catch (Exception e) {
                logger.error("错误监听器中发生异常", e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 生命周期管理
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 释放运行器及其所有已加载的扩展。
     *
     * <p>释放后，将不再发射任何事件。释放过程会：
     * <ol>
     *   <li>设置 disposed 标志位，防止后续操作</li>
     *   <li>依次调用每个扩展的 disposeHandler 清理回调</li>
     *   <li>清空所有扩展列表、处理器映射和错误监听器</li>
     * </ol>
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        for (Extension extension : extensions) {
            try {
                if (extension.disposeHandler() != null) {
                    extension.disposeHandler().run();
                }
            } catch (Exception e) {
                logger.error("释放扩展时出错", e);
            }
        }

        extensions.clear();
        handlers.clear();
        errorListeners.clear();
    }

    /**
     * 获取扩展间通信的事件总线。
     *
     * @return 事件总线实例
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * 获取所有已加载的扩展。
     *
     * @return 已加载扩展的不可变列表
     */
    public List<Extension> getExtensions() {
        return List.copyOf(extensions);
    }

    /**
     * 获取所有扩展中注册的所有工具。
     *
     * @return 所有已注册的工具列表
     */
    public List<RegisteredTool> getAllRegisteredTools() {
        List<RegisteredTool> tools = new ArrayList<>();
        for (Extension extension : extensions) {
            tools.addAll(extension.tools().values());
        }
        return tools;
    }

    /**
     * 获取所有扩展中注册的所有命令。
     *
     * @return 所有已注册的命令列表
     */
    public List<RegisteredCommand> getAllRegisteredCommands() {
        List<RegisteredCommand> commands = new ArrayList<>();
        for (Extension extension : extensions) {
            commands.addAll(extension.commands().values());
        }
        return commands;
    }

    /**
     * 获取所有扩展中注册的所有快捷键。
     *
     * @return 所有已注册的快捷键列表
     */
    public List<RegisteredShortcut> getAllRegisteredShortcuts() {
        List<RegisteredShortcut> shortcuts = new ArrayList<>();
        for (Extension extension : extensions) {
            shortcuts.addAll(extension.shortcuts().values());
        }
        return shortcuts;
    }

    /**
     * 获取所有扩展中注册的所有 CLI 标志位。
     *
     * @return 所有已注册的标志位列表
     */
    public List<RegisteredFlag> getAllRegisteredFlags() {
        List<RegisteredFlag> flags = new ArrayList<>();
        for (Extension extension : extensions) {
            flags.addAll(extension.flags().values());
        }
        return flags;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 创建扩展上下文实例。
     *
     * <p>目前返回一个占位实现，仅提供最基本的上下文信息。
     * TODO: 实现真正的上下文创建，包含实际的会话状态。
     *
     * @return 扩展上下文实例
     */
    private ExtensionContext createContext() {
        // TODO: 实现真正的上下文创建，包含实际的会话状态
        return new ExtensionContextImpl();
    }

    /**
     * 占位上下文实现。
     *
     * <p>提供基本的上下文实现，大部分方法返回空值或默认值。
     * 这是一个临时实现，后续将替换为真正的上下文实现。
     */
    private static class ExtensionContextImpl implements ExtensionContext {
        @Override
        public String getCwd() { return System.getProperty("user.dir"); }

        @Override
        public com.pi.coding.session.SessionManager getSessionManager() { return null; }

        @Override
        public com.pi.ai.core.types.Model getModel() { return null; }

        @Override
        public boolean isIdle() { return true; }

        @Override
        public void abort() { }

        @Override
        public boolean hasPendingMessages() { return false; }

        @Override
        public void shutdown() { }

        @Override
        public ContextUsage getContextUsage() { return null; }

        @Override
        public void compact() { }

        @Override
        public void compact(CompactOptions options) { }

        @Override
        public String getSystemPrompt() { return ""; }

        @Override
        public boolean hasUI() { return false; }
    }
}
