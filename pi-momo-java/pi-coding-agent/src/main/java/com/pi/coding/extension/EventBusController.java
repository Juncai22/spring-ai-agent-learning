package com.pi.coding.extension;

/**
 * 扩展事件总线接口 —— 在 EventBus 基础上增加了控制方法。
 *
 * <p>除了继承自 {@link EventBus} 的订阅和发射方法外，还提供了事件总线
 * 生命周期管理方法，如清空所有订阅者。
 *
 * <p>此接口主要供 {@link ExtensionRunner} 内部使用，用于管理事件总线的生命周期。
 * 扩展通过 {@link ExtensionAPI#getEventBus()} 获取的 {@link EventBus} 实例
 * 实际上就是此接口的实现。
 *
 * <p><b>验证要求：Requirements 22.1-22.5</b>
 */
public interface EventBusController extends EventBus {

    /**
     * 清空事件总线中的所有订阅者。
     *
     * <p>移除所有已注册的事件处理器。此方法通常在扩展释放或重新加载时调用，
     * 以确保事件总线处于干净的状态。
     */
    void clear();

    /**
     * 创建一个新的 EventBusController 实例。
     *
     * <p>工厂方法，返回默认的 {@link EventBusImpl} 实现。
     *
     * @return 一个新的 EventBusController 实例
     */
    static EventBusController create() {
        return new EventBusImpl();
    }
}
