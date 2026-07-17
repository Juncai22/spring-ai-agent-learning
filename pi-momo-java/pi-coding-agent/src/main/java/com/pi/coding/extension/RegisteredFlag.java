package com.pi.coding.extension;

/**
 * 由扩展注册的标志位记录。
 *
 * <p>将标志位定义和注册该标志位的扩展路径关联起来，便于追踪标志位的来源。
 * 可通过 {@link ExtensionRunner#getAllRegisteredFlags()} 获取所有扩展注册的标志位。
 *
 * @param definition    标志位定义
 * @param extensionPath 注册该标志位的扩展路径
 */
public record RegisteredFlag(
    FlagDefinition definition,
    String extensionPath
) { }
