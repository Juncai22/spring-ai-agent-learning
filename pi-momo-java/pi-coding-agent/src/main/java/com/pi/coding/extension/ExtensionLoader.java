package com.pi.coding.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 扩展加载器 —— 发现并加载 Java 扩展模块。
 *
 * <p>扩展加载器负责从多个来源发现实现 {@link ExtensionFactory} 接口的扩展工厂，
 * 并将其加载到 {@link ExtensionRunner} 中。支持以下加载来源：
 * <ul>
 *   <li><b>Java ServiceLoader</b>（META-INF/services）：通过标准的 Java SPI 机制发现扩展，
 *       扩展 JAR 中需包含 META-INF/services/com.pi.coding.extension.ExtensionFactory 文件</li>
 *   <li><b>项目本地目录</b>：{@code {cwd}/.pi/extensions/} 目录下的 JAR 文件</li>
 *   <li><b>全局目录</b>：{@code {agentDir}/extensions/} 目录下的 JAR 文件</li>
 *   <li><b>显式路径</b>：通过配置指定的 JAR 文件路径列表</li>
 * </ul>
 *
 * <p>加载流程：
 * <ol>
 *   <li>通过 ServiceLoader 加载已注册的 ExtensionFactory 实现</li>
 *   <li>扫描项目本地的 .pi/extensions 目录</li>
 *   <li>扫描全局的 extensions 目录</li>
 *   <li>加载显式配置的扩展路径</li>
 *   <li>将所有发现的工厂传递给 ExtensionRunner 加载</li>
 * </ol>
 *
 * <p>重复检测：使用 {@link Set} 对已发现的工厂进行去重，确保同一个扩展不被重复加载。
 *
 * <p><b>验证要求：Requirements 5.1, 5.2</b>
 */
public class ExtensionLoader {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /** 扩展目录名称 */
    private static final String EXTENSIONS_DIR = "extensions";
    /** .pi 配置目录名称 */
    private static final String PI_DIR = ".pi";

    /**
     * 从多个路径发现并加载扩展。
     *
     * <p>综合从 ServiceLoader、项目本地目录、全局目录和显式路径发现扩展工厂，
     * 然后统一加载到 Runner 中。所有发现的错误（包括发现阶段和加载阶段）都会收集并返回。
     *
     * <p><b>验证要求：Requirement 5.1</b>
     *
     * @param paths    显式指定的扩展路径列表
     * @param cwd      当前工作目录，用于解析相对路径和查找项目本地扩展目录
     * @param agentDir Agent 目录（如 ~/.pi），用于查找全局扩展目录
     * @param runner   扩展运行器，负责执行扩展的加载
     * @return 加载结果，包含已加载的扩展列表和加载过程中的错误
     */
    public static LoadExtensionsResult discoverAndLoadExtensions(
            List<String> paths, String cwd, String agentDir, ExtensionRunner runner) {

        List<ExtensionFactory> factories = new ArrayList<>();
        List<LoadExtensionsResult.LoadError> discoveryErrors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 通过 ServiceLoader（Java SPI 机制）加载
        try {
            ServiceLoader<ExtensionFactory> serviceLoader = ServiceLoader.load(ExtensionFactory.class);
            for (ExtensionFactory factory : serviceLoader) {
                String className = factory.getClass().getName();
                if (!seen.contains(className)) {
                    seen.add(className);
                    factories.add(factory);
                    logger.debug("通过 ServiceLoader 发现扩展: {}", className);
                }
            }
        } catch (Exception e) {
            logger.warn("通过 ServiceLoader 加载扩展时出错: {}", e.getMessage());
            discoveryErrors.add(new LoadExtensionsResult.LoadError("ServiceLoader", e.getMessage()));
        }

        // 2. 项目本地扩展：cwd/.pi/extensions/
        Path localExtDir = Path.of(cwd, PI_DIR, EXTENSIONS_DIR);
        discoverExtensionsInDir(localExtDir, factories, discoveryErrors, seen);

        // 3. 全局扩展：agentDir/extensions/
        if (agentDir != null) {
            Path globalExtDir = Path.of(agentDir, EXTENSIONS_DIR);
            discoverExtensionsInDir(globalExtDir, factories, discoveryErrors, seen);
        }

        // 4. 显式配置的路径
        for (String path : paths) {
            Path extPath = Path.of(path);
            if (!extPath.isAbsolute()) {
                extPath = Path.of(cwd).resolve(path);
            }
            discoverExtensionAtPath(extPath, factories, discoveryErrors, seen);
        }

        // 加载所有已发现的工厂
        LoadExtensionsResult loadResult = runner.loadExtensions(factories);

        // 合并发现阶段的错误和加载阶段的错误
        List<LoadExtensionsResult.LoadError> allErrors = new ArrayList<>(discoveryErrors);
        allErrors.addAll(loadResult.errors());

        return new LoadExtensionsResult(loadResult.extensions(), allErrors);
    }

    /**
     * 扫描指定目录下的扩展。
     *
     * <p>递归扫描目录中的文件，查找：
     * <ul>
     *   <li>包含 ExtensionFactory 实现的 JAR 文件</li>
     *   <li>包含扩展模块的子目录</li>
     * </ul>
     *
     * @param dir     要扫描的目录
     * @param factories 已发现的扩展工厂列表（会向此列表添加新发现）
     * @param errors   发现过程中的错误列表（会向此列表添加错误）
     * @param seen     已处理的扩展标识集合，用于去重
     */
    private static void discoverExtensionsInDir(
            Path dir,
            List<ExtensionFactory> factories,
            List<LoadExtensionsResult.LoadError> errors,
            Set<String> seen) {

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(entry -> {
                discoverExtensionAtPath(entry, factories, errors, seen);
            });
        } catch (IOException e) {
            logger.warn("列出扩展目录 {} 时出错: {}", dir, e.getMessage());
            errors.add(new LoadExtensionsResult.LoadError(dir.toString(), e.getMessage()));
        }
    }

    /**
     * 在指定路径发现扩展。
     *
     * <p>根据路径类型处理：
     * <ul>
     *   <li>{@code .jar} 文件：使用 URLClassLoader 加载 JAR 并从中发现 ExtensionFactory</li>
     *   <li>目录：递归扫描目录中的内容</li>
     * </ul>
     *
     * @param path      要检查的路径
     * @param factories 已发现的扩展工厂列表
     * @param errors    发现过程中的错误列表
     * @param seen      已处理的扩展标识集合，用于去重
     */
    private static void discoverExtensionAtPath(
            Path path,
            List<ExtensionFactory> factories,
            List<LoadExtensionsResult.LoadError> errors,
            Set<String> seen) {

        String pathStr = path.toAbsolutePath().toString();
        if (seen.contains(pathStr)) {
            return;
        }

        if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            // 加载 JAR 文件
            try {
                List<ExtensionFactory> jarFactories = loadExtensionsFromJar(path);
                for (ExtensionFactory factory : jarFactories) {
                    String key = pathStr + ":" + factory.getClass().getName();
                    if (!seen.contains(key)) {
                        seen.add(key);
                        factories.add(factory);
                        logger.debug("从 JAR 发现扩展: {} -> {}", path, factory.getClass().getName());
                    }
                }
            } catch (Exception e) {
                logger.warn("加载扩展 JAR {} 时出错: {}", path, e.getMessage());
                errors.add(new LoadExtensionsResult.LoadError(pathStr, e.getMessage()));
            }
        } else if (Files.isDirectory(path)) {
            // 检查目录中的扩展模块
            discoverExtensionsInDir(path, factories, errors, seen);
        }
    }

    /**
     * 从 JAR 文件中加载扩展工厂。
     *
     * <p>使用 {@link java.net.URLClassLoader} 加载 JAR 文件，然后通过 ServiceLoader
     * 机制在 JAR 的类加载器上下文中查找 ExtensionFactory 实现。
     *
     * <p><b>验证要求：Requirement 5.2</b>
     *
     * @param jarPath JAR 文件路径
     * @return 从 JAR 中发现的扩展工厂列表
     * @throws Exception 如果加载过程中发生错误
     */
    private static List<ExtensionFactory> loadExtensionsFromJar(Path jarPath) throws Exception {
        List<ExtensionFactory> factories = new ArrayList<>();

        // 使用 URLClassLoader 加载 JAR 文件
        java.net.URL jarUrl = jarPath.toUri().toURL();
        try (java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
                new java.net.URL[]{jarUrl},
                ExtensionLoader.class.getClassLoader())) {

            // 使用 JAR 的类加载器通过 ServiceLoader 查找扩展工厂
            ServiceLoader<ExtensionFactory> serviceLoader = ServiceLoader.load(ExtensionFactory.class, classLoader);
            for (ExtensionFactory factory : serviceLoader) {
                factories.add(factory);
            }
        }

        return factories;
    }

    /**
     * 从显式的工厂实例加载扩展。
     *
     * <p>适用于程序化注册扩展的场景，无需通过 ServiceLoader 发现机制。
     * 直接将工厂实例传递给 Runner 加载。
     *
     * @param factories 工厂实例列表
     * @param runner   扩展运行器
     * @return 加载结果
     */
    public static LoadExtensionsResult loadExtensions(List<ExtensionFactory> factories, ExtensionRunner runner) {
        return runner.loadExtensions(factories);
    }

    /**
     * 从内联的工厂函数创建并加载一个扩展。
     *
     * <p>适用于快速创建和加载单个扩展的便捷方法。
     * 使用 Lambda 表达式或方法引用创建工厂函数，然后直接加载。
     *
     * @param factory 工厂函数
     * @param runner  扩展运行器
     * @return 加载结果
     */
    public static LoadExtensionsResult loadExtensionFromFactory(ExtensionFactory factory, ExtensionRunner runner) {
        return runner.loadExtensions(List.of(factory));
    }
}
