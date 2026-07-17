package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * 写入操作接口：定义文件写入功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的文件写入实现：
 * <ul>
 *   <li>本地写入：通过 {@link DefaultWriteOperations} 直接写入本地文件系统</li>
 *   <li>远程写入：可通过实现该接口写入远程服务器上的文件</li>
 *   <li>沙箱化写入：可在隔离环境中写入文件</li>
 * </ul>
 *
 * <p>包含两个核心操作：写入文件和创建目录。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface WriteOperations {

    /**
     * 将内容写入文件。
     * <p>
     * 如果文件已存在则覆盖，不存在则创建。
     * 写入使用 UTF-8 编码。
     *
     * @param absolutePath 文件的绝对路径
     * @param content 要写入的文件内容字符串
     * @param signal 取消信号，用于在写入过程中取消操作
     * @return 表示写入操作完成的 CompletableFuture
     */
    CompletableFuture<Void> writeFile(String absolutePath, String content, CancellationSignal signal);

    /**
     * 递归创建目录（包括所有必需的父目录）。
     * <p>
     * 在写入文件前调用，确保目标文件的父目录存在。
     * 如果目录已存在则不会报错。
     *
     * @param dir 要创建的目录路径
     * @param signal 取消信号，用于在创建过程中取消操作
     * @return 表示目录创建完成的 CompletableFuture
     */
    CompletableFuture<Void> mkdir(String dir, CancellationSignal signal);
}
