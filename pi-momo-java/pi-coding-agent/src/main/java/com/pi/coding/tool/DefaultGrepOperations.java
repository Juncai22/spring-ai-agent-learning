package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 默认的 Grep 操作实现：通过本地文件系统使用 Java 正则表达式搜索文件内容。
 *
 * <p>该实现使用 {@link java.util.regex.Pattern} API 进行模式匹配，无需依赖 ripgrep 等外部工具。
 * 主要特性包括：
 * <ul>
 *   <li>正则匹配：使用 Java 标准正则表达式引擎</li>
 *   <li>字面量匹配：通过 {@link Pattern#quote} 将模式转义为字面量</li>
 *   <li>递归搜索：使用 {@code Files.walkFileTree()} 遍历目录树</li>
 *   <li>Glob 过滤：可指定只搜索匹配特定 Glob 模式的文件</li>
 *   <li>自动忽略：遍历时跳过 node_modules 和 .git 目录</li>
 *   <li>二进制文件过滤：通过 MIME 类型检测自动跳过非文本文件</li>
 *   <li>结果限制：达到匹配上限后自动终止遍历</li>
 *   <li>取消支持：支持在搜索过程中取消操作</li>
 *   <li>单文件搜索：支持直接搜索单个文件而非整个目录</li>
 * </ul>
 */
public class DefaultGrepOperations implements GrepOperations {

    /** 当前工作目录，用于解析相对路径 */
    private final String cwd;

    /**
     * 使用指定的工作目录创建默认 Grep 操作实例。
     *
     * @param cwd 当前工作目录，用于解析相对路径
     */
    public DefaultGrepOperations(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public CompletableFuture<Boolean> isDirectory(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> Files.isDirectory(Path.of(absolutePath)));
    }

    @Override
    public CompletableFuture<String> readFile(String absolutePath, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readString(Path.of(absolutePath), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + absolutePath + " - " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<GrepResult> grep(String pattern, String searchPath, GrepOptions options, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            List<GrepMatch> matches = new ArrayList<>();
            boolean[] limitReached = {false};
            Path basePath = Path.of(searchPath);

            // Compile pattern
            Pattern regex;
            try {
                int flags = options.ignoreCase() ? Pattern.CASE_INSENSITIVE : 0;
                String regexPattern = options.literal() ? Pattern.quote(pattern) : pattern;
                regex = Pattern.compile(regexPattern, flags);
            } catch (PatternSyntaxException e) {
                throw new RuntimeException("Invalid regex pattern: " + e.getMessage(), e);
            }

            // Glob pattern matcher
            PathMatcher globMatcher = null;
            if (options.glob() != null && !options.glob().isEmpty()) {
                globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + options.glob());
            }
            final PathMatcher finalGlobMatcher = globMatcher;

            try {
                if (Files.isDirectory(basePath)) {
                    Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (signal != null && signal.isCancelled()) {
                                return FileVisitResult.TERMINATE;
                            }
                            
                            String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
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
                            
                            if (matches.size() >= options.limit()) {
                                limitReached[0] = true;
                                return FileVisitResult.TERMINATE;
                            }

                            // Check glob pattern
                            if (finalGlobMatcher != null) {
                                Path relativePath = basePath.relativize(file);
                                if (!finalGlobMatcher.matches(relativePath) && !finalGlobMatcher.matches(file.getFileName())) {
                                    return FileVisitResult.CONTINUE;
                                }
                            }

                            // Search file
                            searchFile(file, regex, matches, options.limit());
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    // Single file
                    searchFile(basePath, regex, matches, options.limit());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to search: " + e.getMessage(), e);
            }

            return new GrepResult(matches, limitReached[0] || matches.size() >= options.limit());
        });
    }

    /**
     * 在单个文件中搜索匹配模式。
     * <p>
     * 逐行读取文件内容，使用正则表达式进行匹配。
     * 会跳过二进制文件（通过 MIME 类型检测），
     * 并忽略无法读取的文件（如权限不足）。
     *
     * @param file 要搜索的文件路径
     * @param regex 编译后的正则表达式
     * @param matches 匹配结果列表（会向其中添加新的匹配）
     * @param limit 最大匹配结果数
     */
    private void searchFile(Path file, Pattern regex, List<GrepMatch> matches, int limit) {
        try {
            // 跳过二进制文件（通过 MIME 类型检测）
            String contentType = Files.probeContentType(file);
            if (contentType != null && !contentType.startsWith("text/")) {
                return;
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                String line = lines.get(i);
                Matcher matcher = regex.matcher(line);
                if (matcher.find()) {
                    matches.add(new GrepMatch(file.toString(), i + 1, line));
                }
            }
        } catch (IOException e) {
            // 跳过无法读取的文件（如权限不足、非文本文件等）
        }
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
