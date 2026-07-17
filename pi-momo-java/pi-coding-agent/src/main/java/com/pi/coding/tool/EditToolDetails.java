package com.pi.coding.tool;

/**
 * Edit 工具执行结果详情记录。
 *
 * <p>封装了文件编辑操作后的元数据，包括：
 * <ul>
 *   <li>被编辑的文件路径</li>
 *   <li>第一个变更行的行号（用于编辑器导航跳转）</li>
 *   <li>Unified Diff 格式的变更摘要</li>
 * </ul>
 *
 * @param path 被编辑的文件路径（相对或绝对路径）
 * @param firstChangedLine 第一个变更行的行号（1-索引），用于编辑器定位
 * @param diff Unified Diff 格式的变更差异字符串，包含行号和上下文
 */
public record EditToolDetails(
    String path,
    int firstChangedLine,
    String diff
) {}
