package com.pi.coding.extension;

/**
 * 由扩展注册的命令记录。
 *
 * <p>将命令定义和注册该命令的扩展路径关联起来，便于追踪命令的来源。
 * 当多个扩展注册同名命令时，可通过扩展路径区分。
 *
 * @param definition    命令定义
 * @param extensionPath 注册该命令的扩展路径
 */
public record RegisteredCommand(
    CommandDefinition definition,
    String extensionPath
) { }
