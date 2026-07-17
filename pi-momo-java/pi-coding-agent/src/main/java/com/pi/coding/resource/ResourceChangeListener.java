package com.pi.coding.resource;

/**
 * 资源变化监听器接口。
 * 当资源（Skills、Prompts 等）重新加载后被调用。
 *
 * <p>实现此接口以接收资源变化通知，例如在 Skills 热加载后
 * 更新系统提示词。
 *
 * <p>示例用法：
 * <pre>{@code
 * resourceLoader.addChangeListener(event -> {
 *     System.out.println("Skills reloaded: " + event.skillsResult().skills().size());
 * });
 * }</pre>
 */
@FunctionalInterface
public interface ResourceChangeListener {
    
    /**
     * 资源重载完成后的回调。
     *
     * <p>此方法在资源重载完成后被调用，监听器可以在此方法中
     * 执行相应的更新操作，如重建系统提示词。
     *
     * <p>注意：此方法可能在后台线程中被调用，实现时需要考虑线程安全。
     *
     * @param event 资源变化事件，包含重载结果
     */
    void onResourceChanged(ResourceChangeEvent event);
}
