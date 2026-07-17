package com.pi.coding.resource;

import com.pi.coding.util.Frontmatter;
import com.pi.coding.util.FrontmatterResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prompt 模板系统工具类，负责加载、展开和管理提示模板。
 *
 * <p>提示模板是存储在 Markdown 文件中的可复用提示文本，
 * 支持参数占位符，在调用时会被实际参数替换。
 *
 * <p><b>参数占位符支持：</b>
 * <ul>
 *   <li><b>$1, $2, ...</b> — 位置参数，按顺序替换</li>
 *   <li><b>$@ 或 $ARGUMENTS</b> — 所有参数，用空格连接</li>
 *   <li><b>${@:N}</b> — 从第 N 个参数开始的所有参数（Bash 风格切片）</li>
 *   <li><b>${@:N:L}</b> — 从第 N 个参数开始的 L 个参数</li>
 * </ul>
 *
 * <p><b>模板加载来源：</b>
 * <ul>
 *   <li>用户级目录：{agentDir}/prompts/</li>
 *   <li>项目级目录：{cwd}/.kiro/prompts/</li>
 *   <li>显式配置的路径</li>
 * </ul>
 *
 * <p><b>模板解析规则：</b>
 * <ol>
 *   <li>读取文件内容，解析 YAML frontmatter</li>
 *   <li>模板名称取自文件名（不含 .md 扩展名）</li>
 *   <li>描述取自 frontmatter 的 description 字段，或首行非空内容</li>
 *   <li>模板内容为 frontmatter 之后的正文部分</li>
 * </ol>
 */
public final class PromptTemplates {
    
    private PromptTemplates() {
        // Utility class
    }
    
    /**
     * 从所有配置的位置加载 Prompt 模板。
     *
     * <p>加载顺序（优先级从低到高）：
     * <ol>
     *   <li>用户级默认目录：{agentDir}/prompts/</li>
     *   <li>项目级默认目录：{cwd}/.kiro/prompts/</li>
     *   <li>显式配置的路径（支持目录和单个 .md 文件）</li>
     * </ol>
     *
     * @param options 加载选项，包含工作目录、Agent 目录和模板路径列表
     * @return 加载的模板列表
     */
    public static List<PromptTemplate> loadPromptTemplates(LoadPromptTemplatesOptions options) {
        List<PromptTemplate> templates = new ArrayList<>();

        if (options.includeDefaults()) {
            // 加载用户级全局模板
            Path globalPromptsDir = Paths.get(options.agentDir(), "prompts");
            templates.addAll(loadTemplatesFromDir(globalPromptsDir, "user", "(user)"));

            // 加载项目级模板
            Path projectPromptsDir = Paths.get(options.cwd(), ".kiro", "prompts");
            templates.addAll(loadTemplatesFromDir(projectPromptsDir, "project", "(project)"));
        }

        // 加载显式配置的路径
        Path userPromptsDir = Paths.get(options.agentDir(), "prompts");
        Path projectPromptsDir = Paths.get(options.cwd(), ".kiro", "prompts");

        for (String rawPath : options.promptPaths()) {
            Path resolvedPath = resolvePromptPath(rawPath, options.cwd());

            if (!Files.exists(resolvedPath)) {
                continue;
            }

            try {
                SourceInfo sourceInfo = determineSourceInfo(
                    resolvedPath, userPromptsDir, projectPromptsDir, options.includeDefaults()
                );

                if (Files.isDirectory(resolvedPath)) {
                    templates.addAll(loadTemplatesFromDir(
                        resolvedPath, sourceInfo.source(), sourceInfo.label()
                    ));
                } else if (Files.isRegularFile(resolvedPath) &&
                          resolvedPath.toString().endsWith(".md")) {
                    PromptTemplate template = loadTemplateFromFile(
                        resolvedPath, sourceInfo.source(), sourceInfo.label()
                    );
                    if (template != null) {
                        templates.add(template);
                    }
                }
            } catch (Exception e) {
                // 静默忽略读取失败
            }
        }

        return templates;
    }

    /**
     * 如果文本匹配模板名称，则展开 Prompt 模板。
     *
     * <p>文本必须以 / 开头，格式为：
     * <ul>
     *   <li><b>/template-name</b> — 调用模板，无参数</li>
     *   <li><b>/template-name arg1 arg2</b> — 调用模板并传递参数</li>
     * </ul>
     *
     * @param text      要展开的文本（应以 / 开头）
     * @param templates 可用的模板列表
     * @return 展开后的内容，如果非模板格式则返回原始文本
     */
    public static String expandPromptTemplate(String text, List<PromptTemplate> templates) {
        if (!text.startsWith("/")) {
            return text;
        }

        int spaceIndex = text.indexOf(' ');
        String templateName = spaceIndex == -1 ? text.substring(1) : text.substring(1, spaceIndex);
        String argsString = spaceIndex == -1 ? "" : text.substring(spaceIndex + 1);

        PromptTemplate template = templates.stream()
            .filter(t -> t.name().equals(templateName))
            .findFirst()
            .orElse(null);

        if (template != null) {
            List<String> args = parseCommandArgs(argsString);
            return substituteArgs(template.content(), args);
        }

        return text;
    }
    
    /**
     * Parse command arguments respecting quoted strings (bash-style).
     * 
     * @param argsString Arguments string
     * @return List of parsed arguments
     */
    public static List<String> parseCommandArgs(String argsString) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character inQuote = null;
        
        for (int i = 0; i < argsString.length(); i++) {
            char ch = argsString.charAt(i);
            
            if (inQuote != null) {
                if (ch == inQuote) {
                    inQuote = null;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                inQuote = ch;
            } else if (ch == ' ' || ch == '\t') {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        
        if (current.length() > 0) {
            args.add(current.toString());
        }
        
        return args;
    }
    
    /**
     * Substitute argument placeholders in template content.
     * 
     * <p>Supports:
     * <ul>
     *   <li>$1, $2, ... for positional args</li>
     *   <li>$@ and $ARGUMENTS for all args</li>
     *   <li>${@:N} for args from Nth onwards (bash-style slicing)</li>
     *   <li>${@:N:L} for L args starting from Nth</li>
     * </ul>
     * 
     * @param content Template content
     * @param args Arguments to substitute
     * @return Content with substituted arguments
     */
    public static String substituteArgs(String content, List<String> args) {
        String result = content;
        
        // Replace $1, $2, etc. with positional args FIRST
        Pattern positionalPattern = Pattern.compile("\\$(\\d+)");
        Matcher positionalMatcher = positionalPattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (positionalMatcher.find()) {
            int index = Integer.parseInt(positionalMatcher.group(1)) - 1;
            String replacement = index >= 0 && index < args.size() ? args.get(index) : "";
            positionalMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        positionalMatcher.appendTail(sb);
        result = sb.toString();
        
        // Replace ${@:start} or ${@:start:length} with sliced args
        Pattern slicePattern = Pattern.compile("\\$\\{@:(\\d+)(?::(\\d+))?\\}");
        Matcher sliceMatcher = slicePattern.matcher(result);
        sb = new StringBuffer();
        while (sliceMatcher.find()) {
            int start = Integer.parseInt(sliceMatcher.group(1)) - 1; // Convert to 0-indexed
            if (start < 0) start = 0;
            
            String replacement;
            if (sliceMatcher.group(2) != null) {
                int length = Integer.parseInt(sliceMatcher.group(2));
                int end = Math.min(start + length, args.size());
                replacement = String.join(" ", args.subList(start, end));
            } else {
                replacement = String.join(" ", args.subList(start, args.size()));
            }
            sliceMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        sliceMatcher.appendTail(sb);
        result = sb.toString();
        
        // Pre-compute all args joined
        String allArgs = String.join(" ", args);
        
        // Replace $ARGUMENTS with all args
        result = result.replace("$ARGUMENTS", allArgs);
        
        // Replace $@ with all args
        result = result.replace("$@", allArgs);
        
        return result;
    }
    
    // ==================== 内部方法 ====================

    /**
     * 从指定目录加载所有 .md 文件作为模板。
     *
     * @param dir         模板目录
     * @param source      来源标识
     * @param sourceLabel 来源标签（用于描述）
     * @return 模板列表
     */
    private static List<PromptTemplate> loadTemplatesFromDir(
        Path dir,
        String source,
        String sourceLabel
    ) {
        List<PromptTemplate> templates = new ArrayList<>();

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return templates;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(entry -> {
                if (Files.isRegularFile(entry) && entry.toString().endsWith(".md")) {
                    PromptTemplate template = loadTemplateFromFile(entry, source, sourceLabel);
                    if (template != null) {
                        templates.add(template);
                    }
                }
            });
        } catch (IOException e) {
            // 静默忽略目录读取错误
        }

        return templates;
    }

    /**
     * 从单个文件加载 Prompt 模板。
     *
     * <p>解析流程：
     * <ol>
     *   <li>读取文件内容</li>
     *   <li>解析 YAML frontmatter</li>
     *   <li>模板名称 = 文件名（不含 .md 扩展名）</li>
     *   <li>描述 = frontmatter 的 description 字段，或首行非空内容</li>
     *   <li>在描述后追加来源标签</li>
     *   <li>模板内容 = frontmatter 后的正文部分</li>
     * </ol>
     *
     * @param filePath    模板文件路径
     * @param source      来源标识
     * @param sourceLabel 来源标签
     * @return PromptTemplate 对象，加载失败则返回 null
     */
    private static PromptTemplate loadTemplateFromFile(
        Path filePath,
        String source,
        String sourceLabel
    ) {
        try {
            String rawContent = Files.readString(filePath);
            FrontmatterResult frontmatter = Frontmatter.parseFrontmatter(rawContent);

            String fileName = filePath.getFileName().toString();
            String name = fileName.endsWith(".md") ?
                fileName.substring(0, fileName.length() - 3) : fileName;

            // 从 frontmatter 或首行内容获取描述
            String description = frontmatter.getString("description");
            if (description == null || description.isEmpty()) {
                String[] lines = frontmatter.content().split("\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        description = trimmed.length() > 60 ?
                            trimmed.substring(0, 60) + "..." : trimmed;
                        break;
                    }
                }
                if (description == null) {
                    description = "";
                }
            }

            // 在描述后追加来源标签
            description = description.isEmpty() ? sourceLabel : description + " " + sourceLabel;

            return new PromptTemplate(
                name,
                description,
                frontmatter.content(),
                source,
                filePath.toString()
            );

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析模板路径，支持相对路径和 ~ 开头的用户目录路径。
     */
    private static Path resolvePromptPath(String rawPath, String cwd) {
        String normalized = normalizePath(rawPath);
        Path path = Paths.get(normalized);
        return path.isAbsolute() ? path : Paths.get(cwd).resolve(normalized);
    }

    /**
     * 规范化路径，将 ~ 开头的路径替换为用户主目录。
     */
    private static String normalizePath(String input) {
        String trimmed = input.trim();
        String home = System.getProperty("user.home");
        
        if (trimmed.equals("~")) {
            return home;
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return Paths.get(home, trimmed.substring(2)).toString();
        }
        if (trimmed.startsWith("~")) {
            return Paths.get(home, trimmed.substring(1)).toString();
        }
        
        return trimmed;
    }
    
    /**
     * 确定路径的来源信息和标签。
     *
     * @param resolvedPath      解析后的路径
     * @param userPromptsDir    用户级模板目录
     * @param projectPromptsDir 项目级模板目录
     * @param includeDefaults   是否包含默认目录
     * @return 来源信息，包含 source 标识和 label 标签
     */
    private static SourceInfo determineSourceInfo(
        Path resolvedPath,
        Path userPromptsDir,
        Path projectPromptsDir,
        boolean includeDefaults
    ) {
        if (!includeDefaults) {
            if (isUnderPath(resolvedPath, userPromptsDir)) {
                return new SourceInfo("user", "(user)");
            }
            if (isUnderPath(resolvedPath, projectPromptsDir)) {
                return new SourceInfo("project", "(project)");
            }
        }

        String fileName = resolvedPath.getFileName().toString();
        String base = fileName.endsWith(".md") ?
            fileName.substring(0, fileName.length() - 3) : fileName;
        if (base.isEmpty()) {
            base = "path";
        }
        return new SourceInfo("path", "(path:" + base + ")");
    }

    /**
     * 判断目标路径是否在指定根路径下。
     */
    private static boolean isUnderPath(Path target, Path root) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedTarget = target.toAbsolutePath().normalize();
            return normalizedTarget.startsWith(normalizedRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 来源信息内部记录。
     *
     * @param source 来源标识
     * @param label  来源标签（用于显示在描述中）
     */
    private record SourceInfo(String source, String label) {}
}
