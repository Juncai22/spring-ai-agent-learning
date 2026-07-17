package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 查找操作接口：定义文件搜索功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的文件搜索实现：
 * <ul>
 *   <li>本地搜索：通过 {@link DefaultFindOperations} 使用 {@code Files.walkFileTree()} 搜索</li>
 *   <li>远程搜索：可通过实现该接口搜索远程服务器上的文件</li>
 *   <li>沙箱化搜索：可在隔离环境中搜索文件</li>
 * </ul>
 *
 * <p>包含两个核心操作：路径存在性检查和 Glob 模式匹配搜索。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface FindOperations {

    /**
     * 检查路径是否存在。
     * <p>
     * 用于在搜索前验证目标搜索目录是否存在，避免在不存在的路径上执行搜索操作。
     *
     * @param absolutePath 要检查的绝对路径
     * @param signal 取消信号
     * @return 如果路径存在则为 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> exists(String absolutePath, CancellationSignal signal);

    /**
     * 根据 Glob 模式查找匹配的文件。
     * <p>
     * 在指定搜索路径中递归查找所有匹配指定 Glob 模式的文件。
     * 返回的路径相对于搜索路径，且路径分隔符统一为 '/'。
     * 支持忽略指定目录（如 node_modules、.git）。
     *
     * @param pattern Glob 模式字符串（如 "*.java"、"**/*.json"）
     * @param searchPath 搜索的根目录路径
     * @param ignore 要忽略的目录模式列表
     * @param limit 最大返回结果数
     * @param signal 取消信号，用于在搜索过程中取消操作
     * @return 匹配文件路径列表的 CompletableFuture（路径相对于 searchPath）
     */
    CompletableFuture<List<String>> glob(String pattern, String searchPath, List<String> ignore, int limit, CancellationSignal signal);
}
