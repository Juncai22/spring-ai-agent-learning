package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 默认的列表操作实现：通过本地文件系统列出目录内容。
 *
 * <p>该实现使用 {@link java.nio.file.Files#list} API 读取目录条目，主要特性包括：
 * <ul>
 *   <li>非递归列表：只列出指定目录的直接子级，不递归</li>
 *   <li>类型判断：通过 {@code Files.isDirectory()} 判断每个条目是否为目录</li>
 *   <li>包含点文件：不隐藏以 '.' 开头的文件</li>
 *   <li>错误处理：目录读取失败时抛出运行时异常</li>
 * </ul>
 *
 * <p>所有操作均异步执行，通过 {@link CompletableFuture} 返回结果。
 * 相对路径会基于当前工作目录（cwd）解析为绝对路径。
 */
public class DefaultLsOperations implements LsOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认列表操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultLsOperations(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public CompletableFuture<Boolean> exists(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> Files.exists(Path.of(absolutePath)));
    }

    @Override
    public CompletableFuture<Boolean> isDirectory(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> Files.isDirectory(Path.of(absolutePath)));
    }

    @Override
    public CompletableFuture<List<DirEntry>> readdir(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try (Stream<Path> stream = Files.list(Path.of(absolutePath))) {
                List<DirEntry> entries = new ArrayList<>();
                stream.forEach(path -> {
                    entries.add(new DirEntry(
                        path.getFileName().toString(),
                        Files.isDirectory(path)
                    ));
                });
                return entries;
            } catch (IOException e) {
                throw new RuntimeException("Failed to list directory: " + absolutePath + " - " + e.getMessage(), e);
            }
        });
    }

    /**
     * 将路径解析为绝对路径：如果传入路径已经是绝对路径则直接返回，
     * 否则相对于当前工作目录进行解析。
     *
     * @param path 原始路径（相对或绝对）
     * @return 解析后的绝对路径字符串
     */
    public String resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.toString();
        }
        return Paths.get(cwd).resolve(path).toString();
    }
}
