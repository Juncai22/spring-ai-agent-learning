package com.pi.coding.extension;

import java.util.List;
import java.util.Map;

/**
 * 已加载的扩展（Extension），包含该扩展注册的所有组件。
 *
 * <p>扩展是插件系统的核心单元。一个扩展通过 {@link ExtensionFactory} 工厂函数创建，
 * 在工厂函数中使用 {@link ExtensionAPI} 注册工具、命令、快捷键、标志位和事件处理器，
 * 最终由 {@link ExtensionAPIImpl#buildExtension()} 构建为不可变的 Extension 记录。
 *
 * <p>每个扩展实例包含以下注册内容：
 * <ul>
 *   <li>{@code handlers} - 按事件类型分组的事件处理器列表，用于响应 Agent 生命周期事件</li>
 *   <li>{@code tools} - 按名称索引的已注册工具，可供 LLM 调用</li>
 *   <li>{@code commands} - 按名称索引的已注册斜杠命令，用户可在输入框中以 "/" 前缀触发</li>
 *   <li>{@code shortcuts} - 按快捷键键名索引的已注册键盘快捷键</li>
 *   <li>{@code flags} - 按名称索引的已注册 CLI 标志位</li>
 *   <li>{@code disposeHandler} - 扩展销毁时的清理回调（可为 null）</li>
 * </ul>
 *
 * <p>扩展的加载由 {@link ExtensionLoader} 负责发现，由 {@link ExtensionRunner} 负责管理生命周期。
 *
 * @param path            扩展路径，标识该扩展的来源（如 JAR 文件路径或 {@code "<inline>"}）
 * @param handlers        按事件类型分类的已注册事件处理器映射
 * @param tools           按工具名称索引的已注册工具映射
 * @param commands        按命令名称索引的已注册命令映射
 * @param shortcuts       按快捷键键名索引的已注册快捷键映射
 * @param flags           按标志位名称索引的已注册 CLI 标志位映射
 * @param disposeHandler  扩展销毁时的清理回调（可为 null）
 */
public record Extension(
    String path,
    Map<String, List<ExtensionEventHandler<?>>> handlers,
    Map<String, RegisteredTool> tools,
    Map<String, RegisteredCommand> commands,
    Map<String, RegisteredShortcut> shortcuts,
    Map<String, RegisteredFlag> flags,
    Runnable disposeHandler
) { }
