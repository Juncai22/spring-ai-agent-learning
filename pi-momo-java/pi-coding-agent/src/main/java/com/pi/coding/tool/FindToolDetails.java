package com.pi.coding.tool;

/**
 * Find 工具执行结果详情记录。
 *
 * <p>封装了文件搜索操作的详细元数据，包括：
 * <ul>
 *   <li>使用的 Glob 搜索模式</li>
 *   <li>匹配到的文件数量</li>
 *   <li>输出是否被截断</li>
 *   <li>是否达到了结果数量限制</li>
 * </ul>
 *
 * @param pattern 使用的 Glob 搜索模式（如 "*.java"）
 * @param fileCount 匹配到的文件总数
 * @param truncated 输出是否因字节数限制被截断
 * @param resultLimitReached 达到的结果数量限制，如果未达到则为 null
 */
public record FindToolDetails(
    String pattern,
    int fileCount,
    boolean truncated,
    Integer resultLimitReached
) {}
