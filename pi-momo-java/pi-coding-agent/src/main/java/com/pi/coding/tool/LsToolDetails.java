package com.pi.coding.tool;

/**
 * Ls 工具执行结果详情记录。
 *
 * <p>封装了目录列表操作的详细元数据，包括：
 * <ul>
 *   <li>被列出的目录路径</li>
 *   <li>找到的条目数量</li>
 *   <li>输出是否被截断</li>
 *   <li>是否达到了条目数量限制</li>
 * </ul>
 *
 * @param path 被列出的目录路径
 * @param entryCount 找到的目录条目总数
 * @param truncated 输出是否因字节数限制被截断
 * @param entryLimitReached 达到的条目数量限制，如果未达到则为 null
 */
public record LsToolDetails(
    String path,
    int entryCount,
    boolean truncated,
    Integer entryLimitReached
) {}
