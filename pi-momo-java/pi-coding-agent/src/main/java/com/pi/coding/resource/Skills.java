package com.pi.coding.resource;

import com.pi.coding.util.Frontmatter;
import com.pi.coding.util.FrontmatterResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Skills 核心工具类，负责加载、管理和格式化 Agent 技能。
 *
 * <p>技能是 Agent 系统的重要扩展机制，以 SKILL.md 文件形式存储在目录中。
 * 每个技能包含一个名称和描述，Agent 根据任务描述匹配并调用相应的技能文件。
 *
 * <p><b>技能发现规则：</b>
 * <ol>
 *   <li>如果目录包含 SKILL.md，则将该目录视为一个技能根目录，不再递归</li>
 *   <li>如果目录不包含 SKILL.md，则加载根目录下的 .md 文件作为技能</li>
 *   <li>递归进入子目录查找 SKILL.md</li>
 *   <li>支持 .gitignore / .ignore / .fdignore 文件来排除不需要扫描的目录</li>
 * </ol>
 *
 * <p><b>技能校验规则：</b>
 * <ul>
 *   <li>名称必须与父目录名一致</li>
 *   <li>名称只能包含小写字母、数字和连字符</li>
 *   <li>名称不能以连字符开头或结尾，不能包含连续连字符</li>
 *   <li>名称长度不超过 64 个字符</li>
 *   <li>描述不能为空，长度不超过 1024 个字符</li>
 * </ul>
 *
 * <p><b>冲突处理：</b>
 * 当多个来源加载了同名的技能时，遵循"先到先得"原则。
 * 第一个加载的技能保留，后续同名的技能生成冲突诊断。
 * 通过符号链接解析来检测重复文件，避免同一文件被多次加载。
 *
 * <p><b>格式化输出：</b>
 * {@link #formatSkillsForPrompt(List)} 方法将技能列表格式化为 XML 格式，
 * 供 Agent 系统提示词使用。其中 disableModelInvocation 为 true 的技能
 * 不会出现在格式化输出中。
 */
public final class Skills {
    
    /** 技能名称最大长度限制。 */
    private static final int MAX_NAME_LENGTH = 64;

    /** 技能描述最大长度限制。 */
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** 需要解析的忽略规则文件名列表，按优先级排列。 */
    private static final String[] IGNORE_FILE_NAMES = {".gitignore", ".ignore", ".fdignore"};

    /**
     * 私有构造函数，防止实例化。
     * 此类为工具类，所有方法均为静态方法。
     */
    private Skills() {
        // Utility class
    }

    /**
     * 从指定目录加载技能。
     *
     * <p>此方法会扫描指定目录，遵循技能发现规则递归查找 SKILL.md 文件。
     * 在扫描过程中会解析 .gitignore / .ignore / .fdignore 文件，
     * 排除不需要扫描的目录和文件。
     *
     * <p>如果目录不存在，则返回空结果（不会报错）。
     *
     * @param options 加载选项，包含目录路径和来源标识
     * @return 加载结果，包含技能列表和诊断信息
     */
    public static LoadSkillsResult loadSkillsFromDir(LoadSkillsFromDirOptions options) {
        List<Skill> skills = new ArrayList<>();
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        Path dir = Paths.get(options.dir());
        if (!Files.exists(dir)) {
            return new LoadSkillsResult(skills, diagnostics);
        }

        IgnoreRules ignoreRules = new IgnoreRules();
        loadIgnoreRules(ignoreRules, dir, dir);

        loadSkillsFromDirInternal(dir, options.source(), true, ignoreRules, dir, skills, diagnostics);

        return new LoadSkillsResult(skills, diagnostics);
    }
    
    /**
     * 从所有配置的位置加载技能。
     *
     * <p>加载顺序（优先级从低到高）：
     * <ol>
     *   <li>用户级默认目录：{agentDir}/skills/</li>
     *   <li>项目级默认目录：{cwd}/.kiro/skills/</li>
     *   <li>配置中显式指定的路径（支持目录和单个 .md 文件）</li>
     * </ol>
     *
     * <p>冲突处理：
     * <ul>
     *   <li>使用名称去重：同名技能仅保留第一个加载的</li>
     *   <li>使用真实路径去重：通过符号链接解析避免同一文件重复加载</li>
     *   <li>冲突时生成 "collision" 类型的诊断信息</li>
     * </ul>
     *
     * <p>路径处理：
     * <ul>
     *   <li>支持绝对路径和相对路径（相对于 cwd）</li>
     *   <li>支持 ~ 开头的用户目录路径</li>
     *   <li>目录不存在时生成 warning 诊断</li>
     * </ul>
     *
     * @param options 加载选项，包含工作目录、Agent 目录和技能路径列表
     * @return 加载结果，包含去重后的技能列表和诊断信息
     */
    public static LoadSkillsResult loadSkills(LoadSkillsOptions options) {
        Map<String, Skill> skillMap = new LinkedHashMap<>();
        Set<String> realPathSet = new HashSet<>();
        List<ResourceDiagnostic> allDiagnostics = new ArrayList<>();
        List<ResourceDiagnostic> collisionDiagnostics = new ArrayList<>();

        // 从默认位置加载
        if (options.includeDefaults()) {
            Path userSkillsDir = Paths.get(options.agentDir(), "skills");
            addSkills(loadSkillsFromDir(new LoadSkillsFromDirOptions(
                userSkillsDir.toString(), "user"
            )), skillMap, realPathSet, allDiagnostics, collisionDiagnostics);

            Path projectSkillsDir = Paths.get(options.cwd(), ".kiro", "skills");
            addSkills(loadSkillsFromDir(new LoadSkillsFromDirOptions(
                projectSkillsDir.toString(), "project"
            )), skillMap, realPathSet, allDiagnostics, collisionDiagnostics);
        }

        // 从显式路径加载
        for (String rawPath : options.skillPaths()) {
            Path resolvedPath = resolveSkillPath(rawPath, options.cwd());

            if (!Files.exists(resolvedPath)) {
                allDiagnostics.add(new ResourceDiagnostic(
                    "warning", "技能路径不存在", resolvedPath.toString()
                ));
                continue;
            }

            try {
                String source = determineSource(resolvedPath, options);

                if (Files.isDirectory(resolvedPath)) {
                    // 目录：递归加载其中的技能
                    addSkills(loadSkillsFromDir(new LoadSkillsFromDirOptions(
                        resolvedPath.toString(), source
                    )), skillMap, realPathSet, allDiagnostics, collisionDiagnostics);
                } else if (Files.isRegularFile(resolvedPath) && resolvedPath.toString().endsWith(".md")) {
                    // 单个 .md 文件：直接加载
                    LoadSkillFromFileResult result = loadSkillFromFile(resolvedPath, source);
                    if (result.skill() != null) {
                        addSkills(new LoadSkillsResult(
                            List.of(result.skill()), result.diagnostics()
                        ), skillMap, realPathSet, allDiagnostics, collisionDiagnostics);
                    } else {
                        allDiagnostics.addAll(result.diagnostics());
                    }
                } else {
                    allDiagnostics.add(new ResourceDiagnostic(
                        "warning", "技能路径不是 Markdown 文件", resolvedPath.toString()
                    ));
                }
            } catch (Exception e) {
                allDiagnostics.add(new ResourceDiagnostic(
                    "warning", "读取技能路径失败: " + e.getMessage(), resolvedPath.toString()
                ));
            }
        }

        List<ResourceDiagnostic> finalDiagnostics = new ArrayList<>(allDiagnostics);
        finalDiagnostics.addAll(collisionDiagnostics);

        return new LoadSkillsResult(new ArrayList<>(skillMap.values()), finalDiagnostics);
    }
    
    /**
     * 将技能列表格式化为系统提示词可用的 XML 格式。
     *
     * <p>格式遵循 Agent Skills 标准，生成的 XML 结构如下：
     * <pre>{@code
     * <available_skills>
     *   <skill>
     *     <name>skill-name</name>
     *     <description>技能描述</description>
     *     <location>/path/to/SKILL.md</location>
     *   </skill>
     * </available_skills>
     * }</pre>
     *
     * <p><b>过滤规则：</b>
     * <ul>
     *   <li>{@link Skill#disableModelInvocation()} 为 true 的技能会被排除</li>
     *   <li>技能列表为空时返回空字符串</li>
     * </ul>
     *
     * <p>XML 中的特殊字符（&, <, >, ", '）会被自动转义。
     *
     * @param skills 要格式化的技能列表
     * @return 格式化后的 XML 字符串，如果无可用的技能则返回空字符串
     */
    public static String formatSkillsForPrompt(List<Skill> skills) {
        List<Skill> visibleSkills = skills.stream()
            .filter(s -> !s.disableModelInvocation())
            .toList();

        if (visibleSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n以下是针对特定任务提供专用指令的技能列表。\n");
        sb.append("当任务与技能描述匹配时，使用 read 工具加载技能文件。\n");
        sb.append("当技能文件引用相对路径时，请基于技能目录（SKILL.md 的父目录）解析为绝对路径后在工具命令中使用。\n");
        sb.append("\n");
        sb.append("<available_skills>\n");

        for (Skill skill : visibleSkills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
            sb.append("    <description>").append(escapeXml(skill.description())).append("</description>\n");
            sb.append("    <location>").append(escapeXml(skill.filePath())).append("</location>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</available_skills>");

        return sb.toString();
    }
    
    // ==================== 内部方法 ====================

    /**
     * 递归扫描目录，查找并加载技能文件。
     *
     * <p>扫描策略（两遍扫描）：
     * <ol>
     *   <li><b>第一遍：</b>查找 SKILL.md 文件。如果找到，将其作为技能加载并停止递归。</li>
     *   <li><b>第二遍：</b>如果没有 SKILL.md，则：
     *     <ul>
     *       <li>递归进入子目录继续查找</li>
     *       <li>如果 {@code includeRootFiles} 为 true，加载根目录下的 .md 文件</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>忽略规则：
     * <ul>
     *   <li>以 . 开头的文件和目录（隐藏文件）</li>
     *   <li>node_modules 目录</li>
     *   <li>.gitignore / .ignore / .fdignore 中匹配的模式</li>
     * </ul>
     *
     * @param dir              当前要扫描的目录
     * @param source           来源标识（"user" / "project" / "path"）
     * @param includeRootFiles 是否包含根目录下的 .md 文件
     * @param ignoreRules      忽略规则集合
     * @param rootDir          扫描的根目录（用于计算相对路径）
     * @param skills           技能收集列表
     * @param diagnostics      诊断信息收集列表
     */
    private static void loadSkillsFromDirInternal(
        Path dir,
        String source,
        boolean includeRootFiles,
        IgnoreRules ignoreRules,
        Path rootDir,
        List<Skill> skills,
        List<ResourceDiagnostic> diagnostics
    ) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }

        // 加载当前目录的忽略规则
        loadIgnoreRules(ignoreRules, dir, rootDir);

        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> entryList = entries.toList();

            // 第一遍：查找 SKILL.md
            for (Path entry : entryList) {
                if (entry.getFileName().toString().equals("SKILL.md")) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }

                    String relPath = rootDir.relativize(entry).toString().replace('\\', '/');
                    if (ignoreRules.ignores(relPath)) {
                        continue;
                    }

                    LoadSkillFromFileResult result = loadSkillFromFile(entry, source);
                    if (result.skill() != null) {
                        skills.add(result.skill());
                    }
                    diagnostics.addAll(result.diagnostics());
                    return; // 找到 SKILL.md 后不再递归
                }
            }

            // 第二遍：处理子目录和根目录文件
            for (Path entry : entryList) {
                String fileName = entry.getFileName().toString();

                // 跳过隐藏文件和 node_modules
                if (fileName.startsWith(".") || fileName.equals("node_modules")) {
                    continue;
                }

                String relPath = rootDir.relativize(entry).toString().replace('\\', '/');
                boolean isDir = Files.isDirectory(entry);
                String ignorePath = isDir ? relPath + "/" : relPath;

                if (ignoreRules.ignores(ignorePath)) {
                    continue;
                }

                if (isDir) {
                    // 递归进入子目录
                    loadSkillsFromDirInternal(entry, source, false, ignoreRules, rootDir, skills, diagnostics);
                } else if (includeRootFiles && Files.isRegularFile(entry) && fileName.endsWith(".md")) {
                    // 加载根目录下的 .md 文件（仅根目录级别）
                    LoadSkillFromFileResult result = loadSkillFromFile(entry, source);
                    if (result.skill() != null) {
                        skills.add(result.skill());
                    }
                    diagnostics.addAll(result.diagnostics());
                }
            }
        } catch (IOException e) {
            // 静默忽略目录读取错误
        }
    }

    /**
     * 从单个文件加载技能。
     *
     * <p>解析流程：
     * <ol>
     *   <li>读取文件内容</li>
     *   <li>解析 YAML frontmatter，提取 name、description、disable-model-invocation</li>
     *   <li>如果 frontmatter 中没有 name，则使用父目录名作为技能名称</li>
     *   <li>校验名称和描述的有效性</li>
     *   <li>如果描述为空，则不加载该技能</li>
     * </ol>
     *
     * @param filePath 技能文件路径
     * @param source   来源标识
     * @return 加载结果，包含技能对象和诊断信息
     */
    private static LoadSkillFromFileResult loadSkillFromFile(Path filePath, String source) {
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        try {
            String rawContent = Files.readString(filePath);
            FrontmatterResult frontmatter = Frontmatter.parseFrontmatter(rawContent);

            Path skillDir = filePath.getParent();
            String parentDirName = skillDir.getFileName().toString();

            // 从 frontmatter 中提取字段
            String description = frontmatter.getString("description");
            String name = frontmatter.getString("name");
            if (name == null || name.isEmpty()) {
                name = parentDirName; // 默认使用父目录名
            }
            Boolean disableModelInvocation = frontmatter.getBoolean("disable-model-invocation");

            // 校验描述
            List<String> descErrors = validateDescription(description);
            for (String error : descErrors) {
                diagnostics.add(new ResourceDiagnostic("warning", error, filePath.toString()));
            }

            // 校验名称
            List<String> nameErrors = validateName(name, parentDirName);
            for (String error : nameErrors) {
                diagnostics.add(new ResourceDiagnostic("warning", error, filePath.toString()));
            }

            // 如果描述为空，不加载此技能
            if (description == null || description.trim().isEmpty()) {
                return new LoadSkillFromFileResult(null, diagnostics);
            }

            Skill skill = new Skill(
                name,
                description,
                filePath.toString(),
                skillDir.toString(),
                source,
                disableModelInvocation != null && disableModelInvocation
            );

            return new LoadSkillFromFileResult(skill, diagnostics);

        } catch (Exception e) {
            String message = "解析技能文件失败: " + e.getMessage();
            diagnostics.add(new ResourceDiagnostic("warning", message, filePath.toString()));
            return new LoadSkillFromFileResult(null, diagnostics);
        }
    }
    
    /**
     * 校验技能名称的有效性。
     *
     * <p>校验规则：
     * <ul>
     *   <li>名称必须与父目录名一致</li>
     *   <li>名称长度不超过 {@value #MAX_NAME_LENGTH} 个字符</li>
     *   <li>名称只能包含小写字母、数字和连字符（正则：^[a-z0-9-]+$）</li>
     *   <li>名称不能以连字符开头或结尾</li>
     *   <li>名称不能包含连续连字符（--）</li>
     * </ul>
     *
     * @param name          技能名称
     * @param parentDirName 父目录名称
     * @return 校验错误列表，如果校验通过则返回空列表
     */
    private static List<String> validateName(String name, String parentDirName) {
        List<String> errors = new ArrayList<>();

        if (!name.equals(parentDirName)) {
            errors.add("name \"" + name + "\" 与父目录名 \"" + parentDirName + "\" 不匹配");
        }

        if (name.length() > MAX_NAME_LENGTH) {
            errors.add("name 超过 " + MAX_NAME_LENGTH + " 个字符限制（当前 " + name.length() + " 个字符）");
        }

        if (!name.matches("^[a-z0-9-]+$")) {
            errors.add("name 包含无效字符（只能使用小写字母 a-z、数字 0-9 和连字符）");
        }

        if (name.startsWith("-") || name.endsWith("-")) {
            errors.add("name 不能以连字符开头或结尾");
        }

        if (name.contains("--")) {
            errors.add("name 不能包含连续连字符");
        }

        return errors;
    }

    /**
     * 校验技能描述的有效性。
     *
     * <p>校验规则：
     * <ul>
     *   <li>描述不能为空</li>
     *   <li>描述长度不超过 {@value #MAX_DESCRIPTION_LENGTH} 个字符</li>
     * </ul>
     *
     * @param description 技能描述
     * @return 校验错误列表，如果校验通过则返回空列表
     */
    private static List<String> validateDescription(String description) {
        List<String> errors = new ArrayList<>();

        if (description == null || description.trim().isEmpty()) {
            errors.add("description 是必填字段");
        } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("description 超过 " + MAX_DESCRIPTION_LENGTH + " 个字符限制（当前 " + description.length() + " 个字符）");
        }

        return errors;
    }
    
    /**
     * 将加载结果中的技能添加到全局技能映射中。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>解析符号链接获取真实路径，避免同一文件重复加载</li>
     *   <li>如果真实路径已存在，跳过该技能</li>
     *   <li>如果名称已存在，生成 "collision" 诊断信息</li>
     *   <li>如果名称唯一，将技能添加到映射中</li>
     * </ol>
     *
     * @param result             本次加载的结果
     * @param skillMap           全局技能名称到技能对象的映射
     * @param realPathSet        已处理文件的真实路径集合（用于去重）
     * @param allDiagnostics     所有诊断信息收集列表
     * @param collisionDiagnostics 冲突诊断信息收集列表
     */
    private static void addSkills(
        LoadSkillsResult result,
        Map<String, Skill> skillMap,
        Set<String> realPathSet,
        List<ResourceDiagnostic> allDiagnostics,
        List<ResourceDiagnostic> collisionDiagnostics
    ) {
        allDiagnostics.addAll(result.diagnostics());

        for (Skill skill : result.skills()) {
            // 解析符号链接，检测重复文件
            String realPath;
            try {
                realPath = Paths.get(skill.filePath()).toRealPath().toString();
            } catch (IOException e) {
                realPath = skill.filePath();
            }

            // 如果已加载过此文件，跳过
            if (realPathSet.contains(realPath)) {
                continue;
            }

            Skill existing = skillMap.get(skill.name());
            if (existing != null) {
                // 名称冲突，生成冲突诊断
                collisionDiagnostics.add(new ResourceDiagnostic(
                    "collision",
                    "name \"" + skill.name() + "\" 冲突",
                    skill.filePath(),
                    new ResourceCollision(
                        "skill",
                        skill.name(),
                        existing.filePath(),
                        skill.filePath()
                    )
                ));
            } else {
                skillMap.put(skill.name(), skill);
                realPathSet.add(realPath);
            }
        }
    }

    /**
     * 解析技能路径，支持相对路径和 ~ 开头的用户目录路径。
     *
     * @param rawPath 原始路径字符串
     * @param cwd     当前工作目录（用于解析相对路径）
     * @return 解析后的绝对路径
     */
    private static Path resolveSkillPath(String rawPath, String cwd) {
        String normalized = normalizePath(rawPath);
        Path path = Paths.get(normalized);
        return path.isAbsolute() ? path : Paths.get(cwd).resolve(normalized);
    }

    /**
     * 规范化路径，将 ~ 开头的路径替换为用户主目录。
     *
     * @param input 原始路径字符串
     * @return 规范化后的路径字符串
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
     * 确定路径的来源标识。
     *
     * <p>如果路径在用户级或项目级默认目录下，则返回对应的来源标识。
     * 否则返回 "path" 表示是通过显式路径配置加载的。
     *
     * @param resolvedPath 解析后的路径
     * @param options      加载选项
     * @return 来源标识（"user" / "project" / "path"）
     */
    private static String determineSource(Path resolvedPath, LoadSkillsOptions options) {
        if (!options.includeDefaults()) {
            Path userSkillsDir = Paths.get(options.agentDir(), "skills");
            Path projectSkillsDir = Paths.get(options.cwd(), ".kiro", "skills");

            if (isUnderPath(resolvedPath, userSkillsDir)) {
                return "user";
            }
            if (isUnderPath(resolvedPath, projectSkillsDir)) {
                return "project";
            }
        }
        return "path";
    }
    
    /**
     * 判断目标路径是否在指定根路径下。
     *
     * @param target 目标路径
     * @param root   根路径
     * @return 如果目标路径在根路径下返回 true
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
     * 加载指定目录的忽略规则文件（.gitignore / .ignore / .fdignore）。
     *
     * <p>忽略规则会被处理为相对于根目录的路径模式，并添加到 IgnoreRules 集合中。
     * 注释行（以 # 开头）和空行会被忽略。
     *
     * @param ignoreRules 忽略规则集合
     * @param dir         当前目录
     * @param rootDir     扫描根目录（用于计算相对路径前缀）
     */
    private static void loadIgnoreRules(IgnoreRules ignoreRules, Path dir, Path rootDir) {
        String relativeDir = rootDir.relativize(dir).toString().replace('\\', '/');
        String prefix = relativeDir.isEmpty() ? "" : relativeDir + "/";

        for (String filename : IGNORE_FILE_NAMES) {
            Path ignorePath = dir.resolve(filename);
            if (!Files.exists(ignorePath)) {
                continue;
            }

            try {
                String content = Files.readString(ignorePath);
                for (String line : content.split("\\r?\\n")) {
                    String pattern = prefixIgnorePattern(line, prefix);
                    if (pattern != null) {
                        ignoreRules.add(pattern);
                    }
                }
            } catch (IOException e) {
                // 静默忽略读取错误
            }
        }
    }

    /**
     * 为忽略规则中的模式添加目录前缀。
     *
     * <p>处理逻辑：
     * <ul>
     *   <li>去除前后空白</li>
     *   <li>跳过空行和注释行（以 # 开头，\\# 转义除外）</li>
     *   <li>处理 ! 取反操作符</li>
     *   <li>去除开头的 /</li>
     *   <li>在当前目录前缀后拼接模式</li>
     * </ul>
     *
     * @param line   原始模式行
     * @param prefix 当前目录前缀
     * @return 处理后的模式，如果是空行或注释则返回 null
     */
    private static String prefixIgnorePattern(String line, String prefix) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("#") && !trimmed.startsWith("\\#")) {
            return null;
        }

        String pattern = line;
        boolean negated = false;

        if (pattern.startsWith("!")) {
            negated = true;
            pattern = pattern.substring(1);
        } else if (pattern.startsWith("\\!")) {
            pattern = pattern.substring(1);
        }

        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }

        String prefixed = prefix.isEmpty() ? pattern : prefix + pattern;
        return negated ? "!" + prefixed : prefixed;
    }

    /**
     * 转义 XML 特殊字符。
     *
     * @param str 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeXml(String str) {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    // ==================== 辅助内部类 ====================

    /**
     * 忽略规则集合，用于管理 .gitignore 风格的忽略规则。
     *
     * <p>支持简单的 glob 模式匹配，包括：
     * <ul>
     *   <li>* 匹配任意字符序列</li>
     *   <li>? 匹配单个字符</li>
     *   <li>! 前缀表示取反（取消忽略）</li>
     * </ul>
     *
     * <p>匹配时同时检查完整路径和以 "*/" 开头后的路径部分，
     * 模拟 .gitignore 的匹配行为。
     */
    private static class IgnoreRules {
        private final List<String> patterns = new ArrayList<>();

        /**
         * 添加忽略规则模式。
         *
         * @param pattern 忽略模式，支持 ! 前缀取反
         */
        void add(String pattern) {
            patterns.add(pattern);
        }

        /**
         * 判断指定路径是否被忽略。
         *
         * @param path 要检查的路径（相对于根目录）
         * @return 如果路径被忽略返回 true
         */
        boolean ignores(String path) {
            boolean ignored = false;

            for (String pattern : patterns) {
                boolean negated = pattern.startsWith("!");
                String actualPattern = negated ? pattern.substring(1) : pattern;

                if (matches(path, actualPattern)) {
                    ignored = !negated;
                }
            }

            return ignored;
        }

        /**
         * 使用简单 glob 模式匹配路径。
         *
         * @param path    要匹配的路径
         * @param pattern glob 模式
         * @return 如果匹配返回 true
         */
        private boolean matches(String path, String pattern) {
            // 简单 glob 匹配：将 * 和 ? 转换为正则表达式
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");

            return path.matches(regex) || path.matches(".*/" + regex);
        }
    }

    /**
     * 从文件加载技能的结果记录。
     *
     * @param skill      加载的技能对象，如果加载失败则为 null
     * @param diagnostics 加载过程中的诊断信息
     */
    private record LoadSkillFromFileResult(
        Skill skill,
        List<ResourceDiagnostic> diagnostics
    ) {}
}
