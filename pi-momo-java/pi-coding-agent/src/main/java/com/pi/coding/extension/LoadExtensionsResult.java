package com.pi.coding.extension;

import java.util.List;

/**
 * 扩展加载结果 —— 包含已加载的扩展和加载过程中的错误。
 *
 * <p>当扩展加载完成后返回此结果，包含：
 * <ul>
 *   <li>成功加载的扩展列表</li>
 *   <li>加载过程中发生的错误列表</li>
 * </ul>
 *
 * @param extensions 已成功加载的扩展列表
 * @param errors     加载过程中发生的错误列表
 */
public record LoadExtensionsResult(
    List<Extension> extensions,
    List<LoadError> errors
) {

    /**
     * 扩展加载过程中发生的错误。
     *
     * @param path  扩展路径或工厂类名
     * @param error 错误消息
     */
    public record LoadError(
        String path,
        String error
    ) { }
}
