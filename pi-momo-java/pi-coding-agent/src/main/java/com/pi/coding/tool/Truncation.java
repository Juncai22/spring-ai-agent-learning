package com.pi.coding.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 共享的输出截断工具类：对所有工具的输出进行行数和字节数限制。
 *
 * <p>截断策略基于两个独立限制，以先达到者为准：
 * <ul>
 *   <li>行数限制（默认：2000 行）</li>
 *   <li>字节数限制（默认：50KB）</li>
 * </ul>
 *
 * <p>提供两种截断方向：
 * <ul>
 *   <li>头部截断（truncateHead）：保留开头的内容，适用于文件读取场景</li>
 *   <li>尾部截断（truncateTail）：保留末尾的内容，适用于 Bash 命令输出场景</li>
 * </ul>
 *
 * <p>基本原则：不返回不完整的行（除 Bash 尾部截断的边界情况外）。
 * 如果第一行就超过了字节限制，返回空内容并标记 firstLineExceedsLimit=true。
 */
public final class Truncation {

    private Truncation() {
        // 工具类，禁止实例化
    }

    /**
     * 将字节数格式化为人类可读的大小字符串。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>小于 1024 字节：显示为 "{n}B"（如 "500B"）</li>
     *   <li>小于 1MB：显示为 "{n.n}KB"（如 "1.5KB"）</li>
     *   <li>大于等于 1MB：显示为 "{n.n}MB"（如 "2.3MB"）</li>
     * </ul>
     *
     * @param bytes 字节数
     * @return 人类可读的大小字符串，如 "1.5KB"、"2.3MB"
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else {
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        }
    }

    /**
     * 从头部截断内容（保留前 N 行/字节）。
     * 使用默认截断选项（2000 行 / 50KB）。
     * <p>
     * 适用于文件读取场景，希望看到文件开头的内容。
     * 不返回不完整的行。如果第一行就超过了字节限制，
     * 返回空内容并标记 firstLineExceedsLimit=true。
     *
     * @param content 要截断的内容
     * @return 截断结果
     */
    public static TruncationResult truncateHead(String content) {
        return truncateHead(content, new TruncationOptions());
    }

    /**
     * 从头部截断内容（保留前 N 行/字节）。
     * 使用自定义截断选项。
     * <p>
     * 适用于文件读取场景，希望看到文件开头的内容。
     * 不返回不完整的行。如果第一行就超过了字节限制，
     * 返回空内容并标记 firstLineExceedsLimit=true。
     *
     * @param content 要截断的内容
     * @param options 截断选项，包括最大行数和最大字节数
     * @return 截断结果
     */
    public static TruncationResult truncateHead(String content, TruncationOptions options) {
        int maxLines = options.maxLines();
        int maxBytes = options.maxBytes();
        
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        long totalBytes = contentBytes.length;
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        
        // Check if no truncation needed
        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return TruncationResult.noTruncation(content, totalLines, totalBytes);
        }
        
        // Check if first line alone exceeds byte limit
        long firstLineBytes = lines[0].getBytes(StandardCharsets.UTF_8).length;
        if (firstLineBytes > maxBytes) {
            return TruncationResult.truncatedByBytes(
                "",
                totalLines,
                totalBytes,
                0,
                0,
                false,
                true
            );
        }
        
        // Collect complete lines that fit
        List<String> outputLinesArr = new ArrayList<>();
        long outputBytesCount = 0;
        String truncatedBy = "lines";
        
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String line = lines[i];
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + (i > 0 ? 1 : 0); // +1 for newline
            
            if (outputBytesCount + lineBytes > maxBytes) {
                truncatedBy = "bytes";
                break;
            }
            
            outputLinesArr.add(line);
            outputBytesCount += lineBytes;
        }
        
        // If we exited due to line limit
        if (outputLinesArr.size() >= maxLines && outputBytesCount <= maxBytes) {
            truncatedBy = "lines";
        }
        
        String outputContent = String.join("\n", outputLinesArr);
        long finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;
        
        if ("lines".equals(truncatedBy)) {
            return TruncationResult.truncatedByLines(
                outputContent,
                totalLines,
                totalBytes,
                outputLinesArr.size(),
                finalOutputBytes
            );
        } else {
            return TruncationResult.truncatedByBytes(
                outputContent,
                totalLines,
                totalBytes,
                outputLinesArr.size(),
                finalOutputBytes,
                false,
                false
            );
        }
    }
    
    /**
     * 从尾部截断内容（保留后 N 行/字节）。
     * 使用默认截断选项（2000 行 / 50KB）。
     * <p>
     * 适用于 Bash 命令输出场景，希望看到命令执行结果的末尾（错误信息、最终结果）。
     * 从内容末尾开始向前收集行，直到达到限制。
     * 边界情况：如果原始内容的最后一行超过字节限制，可能返回该行的部分内容。
     *
     * @param content 要截断的内容
     * @return 截断结果
     */
    public static TruncationResult truncateTail(String content) {
        return truncateTail(content, new TruncationOptions());
    }

    /**
     * 从尾部截断内容（保留后 N 行/字节）。
     * 使用自定义截断选项。
     * <p>
     * 适用于 Bash 命令输出场景，希望看到命令执行结果的末尾（错误信息、最终结果）。
     * 从内容末尾开始向前收集行，直到达到限制。
     * 边界情况：如果原始内容的最后一行超过字节限制，可能返回该行的部分内容。
     *
     * @param content 要截断的内容
     * @param options 截断选项，包括最大行数和最大字节数
     * @return 截断结果
     */
    public static TruncationResult truncateTail(String content, TruncationOptions options) {
        int maxLines = options.maxLines();
        int maxBytes = options.maxBytes();
        
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        long totalBytes = contentBytes.length;
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        
        // Check if no truncation needed
        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return TruncationResult.noTruncation(content, totalLines, totalBytes);
        }
        
        // Work backwards from the end
        List<String> outputLinesArr = new ArrayList<>();
        long outputBytesCount = 0;
        String truncatedBy = "lines";
        boolean lastLinePartial = false;
        
        for (int i = lines.length - 1; i >= 0 && outputLinesArr.size() < maxLines; i--) {
            String line = lines[i];
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + (outputLinesArr.isEmpty() ? 0 : 1); // +1 for newline
            
            if (outputBytesCount + lineBytes > maxBytes) {
                truncatedBy = "bytes";
                // Edge case: if we haven't added ANY lines yet and this line exceeds maxBytes,
                // take the end of the line (partial)
                if (outputLinesArr.isEmpty()) {
                    String truncatedLine = truncateStringToBytesFromEnd(line, maxBytes);
                    outputLinesArr.add(0, truncatedLine);
                    outputBytesCount = truncatedLine.getBytes(StandardCharsets.UTF_8).length;
                    lastLinePartial = true;
                }
                break;
            }
            
            outputLinesArr.add(0, line);
            outputBytesCount += lineBytes;
        }
        
        // If we exited due to line limit
        if (outputLinesArr.size() >= maxLines && outputBytesCount <= maxBytes) {
            truncatedBy = "lines";
        }
        
        String outputContent = String.join("\n", outputLinesArr);
        long finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;
        
        if ("lines".equals(truncatedBy)) {
            return TruncationResult.truncatedByLines(
                outputContent,
                totalLines,
                totalBytes,
                outputLinesArr.size(),
                finalOutputBytes
            );
        } else {
            return TruncationResult.truncatedByBytes(
                outputContent,
                totalLines,
                totalBytes,
                outputLinesArr.size(),
                finalOutputBytes,
                lastLinePartial,
                false
            );
        }
    }
    
    /**
     * 将单行截断到指定最大长度（字符数）。
     * <p>
     * 如果行长度超过限制，截断后附加 "..." 省略号。
     * 如果 maxLength 小于等于 3，则不添加省略号直接截断。
     *
     * @param line 要截断的行
     * @param maxLength 最大字符数
     * @return 截断后的行（如果截断了则末尾带有 "..."）
     */
    public static String truncateLine(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }
        if (maxLength <= 3) {
            return line.substring(0, maxLength);
        }
        return line.substring(0, maxLength - 3) + "...";
    }

    /**
     * 从末尾截断字符串以符合字节数限制。
     * <p>
     * 正确处理多字节 UTF-8 字符，不会在字符中间截断。
     * 从字符串末尾向前跳过 maxBytes 字节，然后找到有效的 UTF-8 字符边界。
     * UTF-8 延续字节的格式为 10xxxxxx（0x80-0xBF），
     * 如果遇到延续字节则继续向后移动，直到找到字符的起始字节。
     *
     * @param str 要截断的字符串
     * @param maxBytes 最大字节数
     * @return 保留末尾部分、符合字节数限制的字符串
     */
    private static String truncateStringToBytesFromEnd(String str, int maxBytes) {
        byte[] buf = str.getBytes(StandardCharsets.UTF_8);
        if (buf.length <= maxBytes) {
            return str;
        }
        
        // Start from the end, skip maxBytes back
        int start = buf.length - maxBytes;
        
        // Find a valid UTF-8 boundary (start of a character)
        // UTF-8 continuation bytes have the pattern 10xxxxxx (0x80-0xBF)
        while (start < buf.length && (buf[start] & 0xC0) == 0x80) {
            start++;
        }
        
        return new String(buf, start, buf.length - start, StandardCharsets.UTF_8);
    }
}
