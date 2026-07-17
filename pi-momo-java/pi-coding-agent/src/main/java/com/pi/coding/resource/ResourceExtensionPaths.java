package com.pi.coding.resource;

import java.util.List;

/**
 * 扩展模块贡献的额外资源路径集合。
 *
 * <p>当扩展模块（Extension）被加载后，可以通过此记录向 ResourceLoader
 * 贡献额外的资源路径。这些路径会被合并到现有路径中，使 ResourceLoader
 * 能够从扩展模块提供的目录中加载 Skills 和 Prompt 模板。
 *
 * <p>路径合并规则：
 * <ul>
 *   <li>与现有路径合并后自动去重</li>
 *   <li>保持路径的插入顺序</li>
 * </ul>
 *
 * @param extensionPaths 扩展自身的路径列表
 * @param skillPaths     扩展贡献的 Skills 目录路径列表
 * @param promptPaths    扩展贡献的 Prompt 模板目录路径列表
 */
public record ResourceExtensionPaths(
    List<String> extensionPaths,
    List<String> skillPaths,
    List<String> promptPaths
) {
    /**
     * 紧凑构造函数，将 null 值转换为空列表。
     */
    public ResourceExtensionPaths {
        if (extensionPaths == null) extensionPaths = List.of();
        if (skillPaths == null) skillPaths = List.of();
        if (promptPaths == null) promptPaths = List.of();
    }
}
