package com.pi.coding.tool;

/**
 * Read 工具执行结果详情记录。
 *
 * <p>封装了文件读取操作的详细元数据，包括：
 * <ul>
 *   <li>文件路径和总行数</li>
 *   <li>实际输出行数（可能小于总行数）</li>
 *   <li>下次读取的起始偏移量（用于分页续读）</li>
 *   <li>图片文件的 MIME 类型</li>
 *   <li>截断详情（如果发生截断）</li>
 * </ul>
 *
 * @param path 被读取的文件路径
 * @param totalLines 文件总行数
 * @param outputLines 实际输出的行数（截断后可能小于总行数）
 * @param nextOffset 下次续读的起始偏移量，如果没有更多内容则为 null
 * @param mimeType 图片文件的 MIME 类型，文本文件为 null
 * @param truncation 截断结果详情，未截断则为 null
 */
public record ReadToolDetails(
    String path,
    int totalLines,
    int outputLines,
    Integer nextOffset,
    String mimeType,
    TruncationResult truncation
) {
    /**
     * 创建文本文件读取的详情记录。
     * mimeType 固定为 null，truncation 记录截断信息。
     *
     * @param path 文件路径
     * @param totalLines 文件总行数
     * @param outputLines 输出行数
     * @param nextOffset 下次续读偏移量，没有更多内容则为 null
     * @param truncation 截断结果
     * @return 文本文件读取详情
     */
    public static ReadToolDetails forText(
            String path,
            int totalLines,
            int outputLines,
            Integer nextOffset,
            TruncationResult truncation) {
        return new ReadToolDetails(path, totalLines, outputLines, nextOffset, null, truncation);
    }

    /**
     * 创建图片文件读取的详情记录。
     * totalLines、outputLines 和 nextOffset 均为 0/null，
     * mimeType 记录图片格式。
     *
     * @param path 文件路径
     * @param mimeType 图片 MIME 类型
     * @return 图片文件读取详情
     */
    public static ReadToolDetails forImage(String path, String mimeType) {
        return new ReadToolDetails(path, 0, 0, null, mimeType, null);
    }
}
