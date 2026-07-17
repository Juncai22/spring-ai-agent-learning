package com.pi.coding.extension;

import java.util.function.Consumer;

/**
 * 事件总线接口 —— 用于扩展间通信的松耦合事件机制。
 *
 * <p>事件总线允许不同扩展之间通过命名事件进行通信，避免了扩展之间的直接依赖。
 * 一个扩展可以发射事件，其他扩展可以订阅该事件并做出响应。
 *
 * <p>使用方式：
 * <pre>{@code
 * // 订阅事件
 * Runnable unsubscribe = eventBus.on("my_event", data -> { ... });
 *
 * // 发射事件
 * eventBus.emit("my_event", someData);
 *
 * // 取消订阅
 * unsubscribe.run();
 * }</pre>
 *
 * <p>事件总线与扩展事件机制的区别：
 * <ul>
 *   <li>事件总线是扩展间的通信机制，发射和接收方都是扩展</li>
 *   <li>扩展事件（ExtensionEvent）是 Agent 生命周期事件，由 Runner 发射</li>
 * </ul>
 *
 * <p><b>验证要求：Requirements 22.1-22.5</b>
 */
public interface EventBus {

    /**
     * 订阅一个命名事件。
     *
     * <p>注册一个处理器，当指定名称的事件被发射时调用。
     * 返回的 Runnable 可用于取消订阅。
     *
     * @param eventName 事件名称
     * @param handler   事件处理器，接收事件数据
     * @param <T>       事件数据类型
     * @return 一个 Runnable，调用后取消订阅
     */
    <T> Runnable on(String eventName, Consumer<T> handler);

    /**
     * 向所有订阅者发射一个事件。
     *
     * <p>所有已订阅该事件名称的处理器都会被调用。处理器按注册顺序执行。
     * 如果某个处理器抛出异常，不会影响其他处理器的执行。
     *
     * @param eventName 事件名称
     * @param data      事件数据
     * @param <T>       事件数据类型
     */
    <T> void emit(String eventName, T data);
}
