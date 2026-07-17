package com.pi.coding.tool;

/**
 * Grep 工具执行结果详情记录。
 *
 * <p>封装了文件内容搜索操作的详细元数据，包括：
 * <ul>
 *   <li>使用的搜索模式</li>
 *   <li>匹配到的结果数量</li>
 *   <li>输出是否被截断</li>
 *   <li>是否达到了匹配结果数量限制</li>
 *   <li>是否有行被截断</li>
 * </ul>
 *
 * @param pattern 使用的搜索模式字符串
 * @param matchCount 匹配到的结果总数
 * @param truncated 输出是否因字节数限制被截断
 * @param matchLimitReached 达到的匹配结果数量限制，如果未达到则为 null
 * @param linesTruncated 是否有单行内容因超过最大长度被截断
 */
public record GrepToolDetails(
    String pattern,
    int matchCount,
    boolean truncated,
    Integer matchLimitReached,
    boolean linesTruncated
) {}
