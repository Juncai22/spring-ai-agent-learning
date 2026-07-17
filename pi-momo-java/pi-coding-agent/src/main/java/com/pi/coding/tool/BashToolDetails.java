package com.pi.coding.tool;

/**
 * Bash 工具执行结果详情记录。
 *
 * <p>该记录（Record）封装了 Bash 命令执行后的详细元数据，包括：
 * <ul>
 *   <li>执行的命令内容</li>
 *   <li>进程退出码（用于判断命令是否成功）</li>
 *   <li>输出截断时的临时文件路径</li>
 *   <li>输出字节数统计（原始总字节数和实际返回字节数）</li>
 *   <li>截断详情（如果发生了截断）</li>
 * </ul>
 *
 * <p>提供两个工厂方法：
 * <ul>
 *   <li>{@link #simple(String, int, long)} - 未截断的标准执行结果</li>
 *   <li>{@link #truncated(String, int, String, TruncationResult)} - 发生截断时的执行结果</li>
 * </ul>
 *
 * @param command 实际执行的命令字符串
 * @param exitCode 命令执行后的退出码（0 表示成功，非零表示失败）
 * @param tempFilePath 输出被截断时，保存完整输出的临时文件路径；未截断时为 null
 * @param totalBytes 原始完整输出的总字节数
 * @param outputBytes 实际返回给调用方的输出字节数（截断后可能小于 totalBytes）
 * @param truncation 截断操作的结果详情，未截断时为 null
 */
public record BashToolDetails(
    String command,
    int exitCode,
    String tempFilePath,
    long totalBytes,
    long outputBytes,
    TruncationResult truncation
) {
    /**
     * 创建未截断的执行结果详情。
     * 当命令输出未超过截断限制时使用此方法，outputBytes 等于 totalBytes，
     * tempFilePath 和 truncation 均为 null。
     *
     * @param command 执行的命令
     * @param exitCode 退出码
     * @param totalBytes 输出总字节数
     * @return 未截断的执行详情记录
     */
    public static BashToolDetails simple(String command, int exitCode, long totalBytes) {
        return new BashToolDetails(command, exitCode, null, totalBytes, totalBytes, null);
    }

    /**
     * 创建截断后的执行结果详情。
     * 当命令输出超过截断限制时使用此方法，会将完整输出保存到临时文件，
     * 并通过 truncation 参数记录截断的具体信息。
     *
     * @param command 执行的命令
     * @param exitCode 退出码
     * @param tempFilePath 保存完整输出的临时文件路径
     * @param truncation 截断结果详情
     * @return 截断后的执行详情记录
     */
    public static BashToolDetails truncated(
            String command,
            int exitCode,
            String tempFilePath,
            TruncationResult truncation) {
        return new BashToolDetails(
            command, exitCode, tempFilePath,
            truncation.totalBytes(), truncation.outputBytes(), truncation);
    }
}
