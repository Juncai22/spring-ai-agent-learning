package com.pi.coding.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * EventBus 的默认实现 —— 用于扩展间通信的事件总线。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储订阅者，确保线程安全。
 * 使用 {@link CopyOnWriteArrayList} 存储每个事件的处理器列表，支持并发遍历和修改。
 *
 * <p>设计特点：
 * <ul>
 *   <li><b>线程安全</b>：所有数据结构都是并发安全的</li>
 *   <li><b>处理器隔离</b>（要求 22.4）：单个处理器的异常不会影响其他处理器</li>
 *   <li><b>动态订阅/取消</b>：支持在运行时动态添加和移除处理器</li>
 * </ul>
 *
 * <p><b>验证要求：Requirements 22.1-22.5</b>
 */
public class EventBusImpl implements EventBusController {

    private static final Logger logger = LoggerFactory.getLogger(EventBusImpl.class);

    /** 按事件名称分组的订阅者映射，key 为事件名称，value 为处理器列表 */
    private final Map<String, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T> Runnable on(String eventName, Consumer<T> handler) {
        // 获取或创建该事件名称的处理器列表，添加处理器，返回取消订阅的 Runnable
        List<Consumer<?>> handlers = subscribers.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void emit(String eventName, T data) {
        List<Consumer<?>> handlers = subscribers.get(eventName);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }

        // 遍历所有处理器，逐个调用
        for (Consumer<?> handler : handlers) {
            try {
                ((Consumer<T>) handler).accept(data);
            } catch (Exception e) {
                // 记录错误但继续执行其他处理器
                // 验证要求：Requirement 22.4 - 处理器隔离
                logger.warn("事件总线处理器中发生错误，事件 '{}': {}", eventName, e.getMessage(), e);
            }
        }
    }

    /**
     * 清空所有订阅者。
     *
     * <p>移除所有已注册的事件处理器。通常在扩展释放或重新加载时调用。
     */
    @Override
    public void clear() {
        subscribers.clear();
    }
}
