package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 默认的查找操作实现：通过本地文件系统递归搜索文件。
 *
 * <p>该实现使用 {@link java.nio.file.Files#walkFileTree} API 递归遍历目录树，
 * 并使用 {@link java.nio.file.PathMatcher} 进行 Glob 模式匹配。主要特性包括：
 * <ul>
 *   <li>递归遍历：深度优先遍历整个目录树</li>
 *   <li>Glob 匹配：使用标准的 "glob:" 语法进行模式匹配</li>
 *   <li>自动忽略：遍历时跳过 node_modules 和 .git 目录</li>
 *   <li>结果限制：达到结果上限后自动终止遍历</li>
 *   <li>取消支持：支持在遍历过程中取消操作</li>
 *   <li>错误容忍：遇到无法访问的文件时跳过而非报错</li>
 * </ul>
 *
 * <p>所有操作均异步执行，通过 {@link CompletableFuture} 返回结果。
 */
public class DefaultFindOperations implements FindOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认查找操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultFindOperations(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public CompletableFuture<Boolean> exists(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> Files.exists(Path.of(absolutePath)));
    }

    @Override
    public CompletableFuture<List<String>> glob(String pattern, String searchPath, List<String> ignore, int limit, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> results = new ArrayList<>();
            Path basePath = Path.of(searchPath);

            try {
                // Convert glob pattern to PathMatcher
                String globPattern = "glob:" + pattern;
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);

                Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (signal != null && signal.isCancelled()) {
                            return FileVisitResult.TERMINATE;
                        }
                        
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        // Skip ignored directories
                        if (dirName.equals("node_modules") || dirName.equals(".git")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (signal != null && signal.isCancelled()) {
                            return FileVisitResult.TERMINATE;
                        }
                        
                        if (results.size() >= limit) {
                            return FileVisitResult.TERMINATE;
                        }

                        Path relativePath = basePath.relativize(file);
                        if (matcher.matches(relativePath) || matcher.matches(file.getFileName())) {
                            results.add(relativePath.toString().replace('\\', '/'));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // Skip files we can't access
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to search files: " + e.getMessage(), e);
            }

            return results;
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
