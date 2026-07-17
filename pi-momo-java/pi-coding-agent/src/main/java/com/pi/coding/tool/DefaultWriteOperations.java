package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * 默认的写入操作实现：通过本地文件系统直接写入文件。
 *
 * <p>该实现使用 {@link java.nio.file.Files} API 进行文件写入和目录创建操作：
 * <ul>
 *   <li>写入文件：使用 {@code Files.writeString()} 写入 UTF-8 编码内容</li>
 *   <li>创建目录：使用 {@code Files.createDirectories()} 递归创建目录</li>
 * </ul>
 *
 * <p>所有操作均异步执行，通过 {@link CompletableFuture} 返回结果。
 * 相对路径会基于当前工作目录（cwd）解析为绝对路径。
 */
public class DefaultWriteOperations implements WriteOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认写入操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultWriteOperations(String cwd) {
        this.cwd = cwd;
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
    public CompletableFuture<Void> mkdir(String dir, CancellationSignal signal) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(Path.of(dir));
            } catch (IOException e) {
                throw new RuntimeException("Failed to create directory: " + dir + " - " + e.getMessage(), e);
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
