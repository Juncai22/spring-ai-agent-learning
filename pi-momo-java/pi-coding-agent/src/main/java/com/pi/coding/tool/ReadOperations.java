package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * 读取操作接口：定义文件读取功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的文件读取实现：
 * <ul>
 *   <li>本地读取：通过 {@link DefaultReadOperations} 直接读取本地文件系统</li>
 *   <li>远程读取：可通过实现该接口读取远程服务器上的文件</li>
 *   <li>沙箱化读取：可从隔离环境读取文件</li>
 * </ul>
 *
 * <p>包含四个核心操作：文本读取、Base64 读取（用于图片）、行数统计、文件存在性检查。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface ReadOperations {

    /**
     * 以文本形式读取文件内容。
     * <p>
     * 支持从指定行号开始读取（1-索引），用于大文件的分页读取。
     * 返回内容包括文件内容和文件总行数。
     *
     * @param path 文件路径（相对或绝对路径）
     * @param offset 起始行号（1-索引），如果为 null 则从文件开头读取
     * @param signal 取消信号，用于在读取过程中取消操作
     * @return 包含文件内容和总行数的 CompletableFuture
     */
    CompletableFuture<ReadResult> readText(String path, Integer offset, CancellationSignal signal);

    /**
     * 以 Base64 编码形式读取文件内容（用于图片等二进制文件）。
     * <p>
     * 将文件内容编码为 Base64 字符串，便于在 Agent 消息中传输图片附件。
     *
     * @param path 文件路径（相对或绝对路径）
     * @param signal 取消信号，用于在读取过程中取消操作
     * @return Base64 编码的文件内容的 CompletableFuture
     */
    CompletableFuture<String> readBase64(String path, CancellationSignal signal);

    /**
     * 获取文件的总行数。
     * <p>
     * 用于在读取前判断文件大小，或计算续读偏移量。
     *
     * @param path 文件路径（相对或绝对路径）
     * @param signal 取消信号
     * @return 文件总行数的 CompletableFuture
     */
    CompletableFuture<Integer> getLineCount(String path, CancellationSignal signal);

    /**
     * 检查文件是否存在且为普通文件。
     * <p>
     * 用于在读取操作前验证文件是否存在，避免操作不存在的文件。
     *
     * @param path 文件路径（相对或绝对路径）
     * @param signal 取消信号
     * @return 如果文件存在则为 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> exists(String path, CancellationSignal signal);

    /**
     * 文本读取结果记录。
     * <p>
     * 封装了读取到的文本内容和文件的总行数。
     *
     * @param content 读取到的文本内容
     * @param totalLines 文件的总行数
     */
    record ReadResult(
        String content,
        int totalLines
    ) {}
}
