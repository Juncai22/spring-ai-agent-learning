package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * 默认的编辑操作实现：通过本地文件系统直接操作文件。
 *
 * <p>该实现使用 {@link java.nio.file.Files} API 进行文件读写操作，包括：
 * <ul>
 *   <li>读取文件：使用 {@code Files.readAllBytes()} 读取原始字节</li>
 *   <li>写入文件：使用 {@code Files.writeString()} 写入 UTF-8 编码内容</li>
 *   <li>访问检查：使用 {@code Files.exists()}、{@code Files.isReadable()}、{@code Files.isWritable()} 综合判断</li>
 * </ul>
 *
 * <p>所有操作均异步执行，通过 {@link CompletableFuture} 返回结果。
 */
public class DefaultEditOperations implements EditOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认编辑操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultEditOperations(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public CompletableFuture<byte[]> readFile(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(Path.of(absolutePath));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + absolutePath + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> writeFile(String absolutePath, String content, CancellationSignal signal) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(Path.of(absolutePath), content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write file: " + absolutePath + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> access(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = Path.of(absolutePath);
            return Files.exists(path) && Files.isReadable(path) && Files.isWritable(path);
        });
    }

    /**
     * 将路径解析为绝对路径：如果传入路径已经是绝对路径则直接返回，
     * 否则相对于当前工作目录进行解析。
     * <p>
     * 此方法用于统一处理 EditTool 和 DefaultEditOperations 中的路径解析逻辑。
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
