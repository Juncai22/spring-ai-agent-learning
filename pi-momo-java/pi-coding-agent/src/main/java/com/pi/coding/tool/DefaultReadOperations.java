package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 默认的读取操作实现：通过本地文件系统直接读取文件。
 *
 * <p>该实现使用 {@link java.nio.file.Files} API 进行文件读取操作，包括：
 * <ul>
 *   <li>文本读取：使用 {@code Files.lines()} 和 {@code BufferedReader} 实现行级读取</li>
 *   <li>Base64 读取：使用 {@code Files.readAllBytes()} 读取原始字节后编码</li>
 *   <li>行数统计：使用 {@code Stream.count()} 高效统计行数</li>
 *   <li>存在性检查：使用 {@code Files.exists()} 和 {@code Files.isRegularFile()} 综合判断</li>
 * </ul>
 *
 * <p>所有操作均异步执行，通过 {@link CompletableFuture} 返回结果。
 * 相对路径会基于当前工作目录（cwd）解析为绝对路径。
 */
public class DefaultReadOperations implements ReadOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认读取操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultReadOperations(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public CompletableFuture<ReadResult> readText(String path, Integer offset, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = resolvePath(path);
                
                // Count total lines
                int totalLines;
                try (Stream<String> lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                    totalLines = (int) lines.count();
                }
                
                // Read content with offset
                String content;
                int startLine = offset != null ? Math.max(1, offset) : 1;
                
                try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    content = reader.lines()
                        .skip(startLine - 1)
                        .collect(Collectors.joining("\n"));
                }
                
                return new ReadResult(content, totalLines);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + path + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> readBase64(String path, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = resolvePath(path);
                byte[] bytes = Files.readAllBytes(filePath);
                return Base64.getEncoder().encodeToString(bytes);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + path + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getLineCount(String path, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = resolvePath(path);
                try (Stream<String> lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                    return (int) lines.count();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to count lines: " + path + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(String path, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            Path filePath = resolvePath(path);
            return Files.exists(filePath) && Files.isRegularFile(filePath);
        });
    }

    private Path resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p;
        }
        return Paths.get(cwd).resolve(path);
    }
}
