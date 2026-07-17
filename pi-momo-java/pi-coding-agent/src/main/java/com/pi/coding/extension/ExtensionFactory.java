package com.pi.coding.extension;

/**
 * 扩展工厂函数接口 —— 扩展的创建入口点。
 *
 * <p>扩展的创建通过实现此接口的工厂函数完成。工厂函数接收一个 {@link ExtensionAPI} 实例，
 * 并使用它来注册工具、命令、快捷键、标志位和事件处理器。
 *
 * <p>此接口是 {@link FunctionalInterface}，可用 Lambda 表达式或方法引用实现。
 * 扩展的加载流程：
 * <ol>
 *   <li>{@link ExtensionLoader} 发现 ExtensionFactory 实现</li>
 *   <li>{@link ExtensionRunner} 调用工厂的 {@code create} 方法</li>
 *   <li>工厂方法接收 ExtensionAPI 并注册各种组件</li>
 *   <li>Runner 构建不可变的 {@link Extension} 记录</li>
 * </ol>
 *
 * <p>示例：
 * <pre>{@code
 * ExtensionFactory factory = api -> {
 *     api.registerTool(ToolDefinition.builder()
 *         .name("my_tool")
 *         .description("My custom tool")
 *         .executor((id, params, signal, update, ctx) -> { ... })
 *         .build());
 * };
 * }</pre>
 */
@FunctionalInterface
public interface ExtensionFactory {

    /**
     * 创建扩展。
     *
     * <p>在此方法中，使用传入的 {@code api} 注册扩展所需的工具、命令、快捷键、
     * 标志位和事件处理器。此方法调用完成后，Runner 会将所有注册信息收集起来
     * 构建为不可变的 Extension 记录。
     *
     * @param api 扩展 API，用于注册各种组件和与运行时交互
     */
    void create(ExtensionAPI api);
}
