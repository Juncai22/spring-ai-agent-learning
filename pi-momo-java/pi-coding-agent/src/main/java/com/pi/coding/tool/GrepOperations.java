package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * Grep 操作接口：定义文件内容搜索功能的统一契约。
 *
 * <p>该接口采用策略模式，允许不同的文件搜索实现：
 * <ul>
 *   <li>本地搜索：通过 {@link DefaultGrepOperations} 使用 Java 正则表达式搜索</li>
 *   <li>远程搜索：可通过实现该接口搜索远程服务器上的文件内容</li>
 *   <li>沙箱化搜索：可在隔离环境中搜索文件内容</li>
 * </ul>
 *
 * <p>包含三个核心操作：目录判断、文件读取和模式搜索。
 * 所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface GrepOperations {

    /**
     * 检查路径是否为目录。
     * <p>
     * 用于判断搜索目标是一个目录（需要递归搜索）还是单个文件（直接搜索）。
     *
     * @param absolutePath 要检查的绝对路径
     * @param signal 取消信号
     * @return 如果路径是目录则为 true 的 CompletableFuture
     */
    CompletableFuture<Boolean> isDirectory(String absolutePath, CancellationSignal signal);

    /**
     * 读取文件内容。
     * <p>
     * 以 UTF-8 编码读取整个文件内容，用于对单个文件进行搜索。
     *
     * @param absolutePath 文件的绝对路径
     * @param signal 取消信号，用于在读取过程中取消操作
     * @return 文件内容字符串的 CompletableFuture
     */
    CompletableFuture<String> readFile(String absolutePath, CancellationSignal signal);

    /**
     * 在文件中搜索模式。
     * <p>
     * 核心搜索方法，支持在目录中递归搜索或在单个文件中搜索。
     * 支持正则表达式和字面量两种搜索模式。
     *
     * @param pattern 搜索模式（正则表达式或字面量字符串）
     * @param searchPath 搜索路径（目录或文件）
     * @param options 搜索选项，包括 Glob 过滤、大小写敏感、字面量模式等
     * @param signal 取消信号，用于在搜索过程中取消操作
     * @return 搜索结果的 CompletableFuture
     */
    CompletableFuture<GrepResult> grep(String pattern, String searchPath, GrepOptions options, CancellationSignal signal);

    /**
     * Grep 搜索选项记录。
     * <p>
     * 封装了搜索行为的所有配置参数。
     *
     * @param glob 文件过滤器 Glob 模式，为 null 时不限制文件类型
     * @param ignoreCase 是否忽略大小写
     * @param literal 是否将模式视为字面量字符串而非正则表达式
     * @param context 匹配行前后显示的上下文行数
     * @param limit 最大匹配结果数
     */
    record GrepOptions(
        String glob,
        boolean ignoreCase,
        boolean literal,
        int context,
        int limit
    ) {}

    /**
     * Grep 搜索结果记录。
     * <p>
     * 包含所有匹配结果和是否达到限制的标记。
     *
     * @param matches 匹配结果列表
     * @param limitReached 是否已达到匹配结果数量限制
     */
    record GrepResult(
        java.util.List<GrepMatch> matches,
        boolean limitReached
    ) {}

    /**
     * 单个 Grep 匹配结果记录。
     * <p>
     * 包含匹配的文件路径、行号和行内容。
     *
     * @param filePath 匹配文件路径
     * @param lineNumber 匹配行号（1-索引）
     * @param lineContent 匹配行的内容
     */
    record GrepMatch(
        String filePath,
        int lineNumber,
        String lineContent
    ) {}
}
