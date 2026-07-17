package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * 编辑操作接口：定义文件编辑功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的文件编辑实现：
 * <ul>
 *   <li>本地编辑：通过 {@link DefaultEditOperations} 直接操作本地文件系统</li>
 *   <li>远程编辑：可通过实现该接口编辑远程服务器上的文件</li>
 *   <li>沙箱化编辑：可在隔离环境中编辑文件</li>
 * </ul>
 *
 * <p>包含三个核心操作：读取文件（字节数组）、写入文件、检查文件可访问性。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface EditOperations {

    /**
     * 以字节数组形式读取文件内容。
     * <p>
     * 返回原始字节数组而非字符串，以支持二进制文件或需要特殊编码处理的场景。
     * 调用方负责将字节解码为字符串。
     *
     * @param absolutePath 文件的绝对路径
     * @param signal 取消信号，用于在读取过程中取消操作
     * @return 包含文件内容的字节数组的 CompletableFuture
     */
    CompletableFuture<byte[]> readFile(String absolutePath, CancellationSignal signal);

    /**
     * 将内容写入文件。
     * <p>
     * 如果文件存在则覆盖，不存在则创建。
     * 写入使用 UTF-8 编码。
     *
     * @param absolutePath 文件的绝对路径
     * @param content 要写入的文件内容字符串
     * @param signal 取消信号，用于在写入过程中取消操作
     * @return 表示写入操作完成的 CompletableFuture
     */
    CompletableFuture<Void> writeFile(String absolutePath, String content, CancellationSignal signal);

    /**
     * 检查文件是否存在且可读写。
     * <p>
     * 用于在编辑操作前验证文件是否可访问，避免在文件不存在或权限不足时执行操作。
     *
     * @param absolutePath 文件的绝对路径
     * @param signal 取消信号
     * @return 如果文件存在且可读写则返回 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> access(String absolutePath, CancellationSignal signal);
}
