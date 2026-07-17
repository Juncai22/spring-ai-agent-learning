package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 列表操作接口：定义目录列表功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的目录列表实现：
 * <ul>
 *   <li>本地列表：通过 {@link DefaultLsOperations} 使用 {@code Files.list()} 列出目录</li>
 *   <li>远程列表：可通过实现该接口列出远程服务器上的目录</li>
 *   <li>沙箱化列表：可在隔离环境中列出目录</li>
 * </ul>
 *
 * <p>包含三个核心操作：路径存在性检查、目录判断和目录条目读取。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface LsOperations {

    /**
     * 检查路径是否存在。
     * <p>
     * 用于在列表前验证目标路径是否存在。
     *
     * @param absolutePath 要检查的绝对路径
     * @param signal 取消信号
     * @return 如果路径存在则为 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> exists(String absolutePath, CancellationSignal signal);

    /**
     * 检查路径是否为目录。
     * <p>
     * 用于验证目标路径确实是目录而非普通文件，因为 Ls 工具只能列出目录内容。
     *
     * @param absolutePath 要检查的绝对路径
     * @param signal 取消信号
     * @return 如果路径是目录则为 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> isDirectory(String absolutePath, CancellationSignal signal);

    /**
     * 读取目录中的所有条目。
     * <p>
     * 返回目录中的所有文件和子目录条目，每个条目包含名称和是否为目录的标记。
     * 不会递归列出子目录的内容。
     *
     * @param absolutePath 目录的绝对路径
     * @param signal 取消信号，用于在读取过程中取消操作
     * @return 目录条目列表的 CompletableFuture，每个条目包含名称和类型信息
     */
    CompletableFuture<List<DirEntry>> readdir(String absolutePath, CancellationSignal signal);

    /**
     * 目录条目记录。
     * <p>
     * 表示目录中的一个文件或子目录条目。
     *
     * @param name 条目名称（不包含路径）
     * @param isDirectory 是否为子目录
     */
    record DirEntry(
        String name,
        boolean isDirectory
    ) {}
}
