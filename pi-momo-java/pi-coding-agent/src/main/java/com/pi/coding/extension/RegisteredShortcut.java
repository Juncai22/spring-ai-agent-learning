package com.pi.coding.extension;

/**
 * 由扩展注册的快捷键记录。
 *
 * <p>将快捷键定义和注册该快捷键的扩展路径关联起来，便于追踪快捷键的来源。
 * 可通过 {@link ExtensionRunner#getAllRegisteredShortcuts()} 获取所有扩展注册的快捷键。
 *
 * @param definition    快捷键定义
 * @param extensionPath 注册该快捷键的扩展路径
 */
public record RegisteredShortcut(
    ShortcutDefinition definition,
    String extensionPath
) { }
