package com.pi.coding.tool;

/**
 * 截断操作选项记录。
 *
 * <p>配置截断操作的两个限制参数：
 * <ul>
 *   <li>maxLines：最大行数限制，超过此行数则截断（默认：2000）</li>
 *   <li>maxBytes：最大字节数限制，超过此字节数则截断（默认：50KB）</li>
 * </ul>
 *
 * <p>截断时以先达到的限制为准。例如，如果内容有 3000 行但只有 10KB，
 * 则会按行数限制截断；如果内容有 500 行但有 100KB，则会按字节数限制截断。
 *
 * @param maxLines 最大行数限制（默认：2000）
 * @param maxBytes 最大字节数限制（默认：50KB = 51200 字节）
 */
public record TruncationOptions(
    int maxLines,
    int maxBytes
) {
    /** 默认最大行数：2000 行 */
    public static final int DEFAULT_MAX_LINES = 2000;

    /** 默认最大字节数：50KB（51200 字节） */
    public static final int DEFAULT_MAX_BYTES = 50 * 1024;

    /**
     * 创建默认截断选项（2000 行 / 50KB）。
     */
    public TruncationOptions() {
        this(DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    /**
     * 创建自定义行数限制、默认字节数限制的截断选项。
     *
     * @param maxLines 最大行数限制
     */
    public TruncationOptions(int maxLines) {
        this(maxLines, DEFAULT_MAX_BYTES);
    }
}
