package com.pi.coding.extension;

/**
 * 由扩展注册的工具记录。
 *
 * <p>将工具定义和注册该工具的扩展路径关联起来，便于追踪工具的来源。
 * 可通过 {@link ExtensionRunner#getAllRegisteredTools()} 获取所有扩展注册的工具。
 *
 * @param definition    工具定义
 * @param extensionPath 注册该工具的扩展路径
 */
public record RegisteredTool(
    ToolDefinition<?> definition,
    String extensionPath
) { }
