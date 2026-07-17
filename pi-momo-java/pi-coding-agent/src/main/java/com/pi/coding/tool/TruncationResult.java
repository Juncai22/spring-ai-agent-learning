package com.pi.coding.tool;

/**
 * 截断操作结果记录。
 *
 * <p>封装了截断操作的完整结果信息，包括：
 * <ul>
 *   <li>截断后的内容</li>
 *   <li>是否发生了截断</li>
 *   <li>触发的限制类型（行数限制或字节数限制）</li>
 *   <li>原始内容的行数和字节数</li>
 *   <li>截断后的行数和字节数</li>
 *   <li>边界情况标记（最后一行部分截断、第一行超过限制）</li>
 * </ul>
 *
 * <p>提供三个工厂方法：
 * <ul>
 *   <li>{@link #noTruncation(String, int, long)} - 未截断的完整结果</li>
 *   <li>{@link #truncatedByLines(String, int, long, int, long)} - 按行数限制截断</li>
 *   <li>{@link #truncatedByBytes(String, int, long, int, long, boolean, boolean)} - 按字节数限制截断</li>
 * </ul>
 *
 * @param content 截断后的内容（如果未截断则为完整内容）
 * @param truncated 是否发生了截断
 * @param truncatedBy 触发的限制类型："lines"（行数限制）、"bytes"（字节数限制），未截断时为 null
 * @param totalLines 原始内容的总行数
 * @param totalBytes 原始内容的总字节数
 * @param outputLines 截断后输出的完整行数
 * @param outputBytes 截断后输出的字节数
 * @param lastLinePartial 最后一行是否被部分截断（仅尾部截断的边界情况）
 * @param firstLineExceedsLimit 第一行是否超过了字节限制（仅头部截断）
 */
public record TruncationResult(
    String content,
    boolean truncated,
    String truncatedBy,
    int totalLines,
    long totalBytes,
    int outputLines,
    long outputBytes,
    boolean lastLinePartial,
    boolean firstLineExceedsLimit
) {
    /**
     * 创建未截断的结果。
     * <p>
     * 当原始内容未超过任何限制时使用，truncated=false，
     * outputLines 等于 totalLines，outputBytes 等于 totalBytes。
     *
     * @param content 完整内容
     * @param totalLines 总行数
     * @param totalBytes 总字节数
     * @return 未截断的结果
     */
    public static TruncationResult noTruncation(String content, int totalLines, long totalBytes) {
        return new TruncationResult(
            content,
            false,
            null,
            totalLines,
            totalBytes,
            totalLines,
            totalBytes,
            false,
            false
        );
    }

    /**
     * 创建按行数限制截断的结果。
     * <p>
     * 当内容行数超过 maxLines 限制时使用，truncatedBy="lines"。
     * lastLinePartial 和 firstLineExceedsLimit 均为 false，
     * 因为行数截断不会截断行内的内容。
     *
     * @param content 截断后的内容
     * @param totalLines 原始总行数
     * @param totalBytes 原始总字节数
     * @param outputLines 输出行数
     * @param outputBytes 输出字节数
     * @return 按行数截断的结果
     */
    public static TruncationResult truncatedByLines(
            String content,
            int totalLines,
            long totalBytes,
            int outputLines,
            long outputBytes) {
        return new TruncationResult(
            content,
            true,
            "lines",
            totalLines,
            totalBytes,
            outputLines,
            outputBytes,
            false,
            false
        );
    }

    /**
     * 创建按字节数限制截断的结果。
     * <p>
     * 当内容字节数超过 maxBytes 限制时使用，truncatedBy="bytes"。
     * 可能包含边界情况标记：
     * <ul>
     *   <li>lastLinePartial：尾部截断时，最后一行超出字节限制被部分截断</li>
     *   <li>firstLineExceedsLimit：头部截断时，第一行单独就超过了字节限制</li>
     * </ul>
     *
     * @param content 截断后的内容
     * @param totalLines 原始总行数
     * @param totalBytes 原始总字节数
     * @param outputLines 输出行数
     * @param outputBytes 输出字节数
     * @param lastLinePartial 最后一行是否被部分截断
     * @param firstLineExceedsLimit 第一行是否超过字节限制
     * @return 按字节数截断的结果
     */
    public static TruncationResult truncatedByBytes(
            String content,
            int totalLines,
            long totalBytes,
            int outputLines,
            long outputBytes,
            boolean lastLinePartial,
            boolean firstLineExceedsLimit) {
        return new TruncationResult(
            content,
            true,
            "bytes",
            totalLines,
            totalBytes,
            outputLines,
            outputBytes,
            lastLinePartial,
            firstLineExceedsLimit
        );
    }
}
