package com.pi.coding.util;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * 用于解析 Markdown 文件中 YAML 前置元数据（Frontmatter）的工具类。
 *
 * <p>Frontmatter 是位于文件开头的 YAML 配置块，由 "---" 分隔符包裹，
 * 常用于在 Markdown 文档中嵌入元数据（如标题、日期、标签等）。格式如下：
 * <pre>
 * ---
 * title: 示例标题
 * date: 2024-01-01
 * tags: [java, markdown]
 * ---
 *
 * 这里是文档正文内容...
 * </pre>
 *
 * <p>本类使用 SnakeYAML 库解析 YAML 内容，提供前置元数据的提取、解析和剥离功能。
 * 支持 Windows (\r\n)、旧 Mac (\r) 和 Unix (\n) 三种换行符的自动归一化处理。
 *
 * @author pi-coding
 */
public final class Frontmatter {

    /**
     * 私有构造方法，防止实例化工具类。
     * 本类所有方法均为静态方法，无需创建实例。
     */
    private Frontmatter() {
        // 工具类，禁止实例化
    }

    /**
     * 从给定的文本内容中解析 YAML 前置元数据（Frontmatter）。
     * <p>
     * 解析流程：
     * <ol>
     *   <li>检查内容是否为空，为空则直接返回空结果</li>
     *   <li>归一化换行符（统一为 \n），避免跨平台换行符差异导致解析错误</li>
     *   <li>检查是否以 "---" 开头，若非则视为无前置元数据，返回原内容作为正文</li>
     *   <li>查找闭合的 "---" 分隔符，若未找到则返回原内容</li>
     *   <li>提取两个分隔符之间的 YAML 字符串，并解析为 Map 结构</li>
     *   <li>提取正文内容（闭合分隔符之后的部分）</li>
     *   <li>返回包含元数据 Map 和正文内容的 {@link FrontmatterResult} 对象</li>
     * </ol>
     *
     * @param content 待解析的文本内容，可为 null 或空字符串
     * @return 解析结果，包含前置元数据 Map 和正文内容。若解析失败或没有前置元数据，
     *         元数据 Map 为空（{@link Map#of()}），正文为原始内容
     */
    public static FrontmatterResult parseFrontmatter(String content) {
        // 处理空内容：传入 null 或空字符串时，直接返回空的解析结果
        if (content == null || content.isEmpty()) {
            return new FrontmatterResult(Map.of(), "");
        }

        // 归一化换行符，统一为 \n，以兼容 Windows (\r\n)、旧版 Mac (\r) 和 Unix (\n) 三种换行格式
        String normalized = normalizeNewlines(content);

        // 检查是否以 "---" 开头；如果不是，则说明没有前置元数据，直接返回原内容作为正文
        if (!normalized.startsWith("---")) {
            return new FrontmatterResult(Map.of(), content);
        }

        // 查找闭合的 "---" 分隔符：从索引 3（即首个 "---" 之后）开始查找 "\n---"
        int endIndex = normalized.indexOf("\n---", 3);
        if (endIndex == -1) {
            // 没有找到闭合分隔符，说明前置元数据块不完整，返回原始内容
            return new FrontmatterResult(Map.of(), content);
        }

        // 提取 YAML 字符串：位于 "---\n"（索引 0-3）和 "\n---"（endIndex）之间
        // 起始索引为 4（跳过 "---\n" 共 4 个字符），结束索引为 endIndex
        String yamlString;
        if (endIndex > 4) {
            // 正常情况：YAML 内容非空，提取中间部分
            yamlString = normalized.substring(4, endIndex);
        } else {
            // 边界情况：空 Frontmatter，即 "---\n---" 格式，中间无内容
            yamlString = "";
        }

        // 提取正文内容：位于闭合分隔符 "\n---" 之后的部分
        String body;
        int bodyStart = endIndex + 4; // 跳过 "\n---" 共 4 个字符
        if (bodyStart < normalized.length()) {
            // 如果闭合分隔符后有换行符，则跳过该换行符，使正文起始不包含多余空行
            if (normalized.charAt(bodyStart) == '\n') {
                bodyStart++;
            }
            // 提取正文：从 bodyStart 到字符串末尾
            body = bodyStart < normalized.length() ? normalized.substring(bodyStart) : "";
        } else {
            // 边界情况：闭合分隔符后没有内容，正文为空字符串
            body = "";
        }

        // 使用 SnakeYAML 解析 YAML 字符串为 Map 结构
        Map<String, Object> data;
        try {
            if (yamlString.isEmpty()) {
                // 空 YAML 字符串，返回空 Map
                data = Map.of();
            } else {
                Yaml yaml = new Yaml();
                Object parsed = yaml.load(yamlString);
                if (parsed instanceof Map) {
                    // YAML 解析结果为 Map 类型，进行类型安全的强制转换
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    data = map;
                } else {
                    // YAML 解析结果不是 Map（如字符串、列表等），返回空 Map
                    data = Map.of();
                }
            }
        } catch (Exception e) {
            // YAML 解析异常（如格式错误、非法字符等），容错处理，返回空前置元数据
            data = Map.of();
        }

        // 返回包含解析结果的对象
        return new FrontmatterResult(data, body);
    }

    /**
     * 从文本内容中剥离 YAML 前置元数据（Frontmatter），仅返回正文部分。
     * <p>
     * 此方法是 {@link #parseFrontmatter(String)} 的便捷封装，
     * 当调用者只关心正文内容、不需要前置元数据时使用。
     *
     * @param content 包含前置元数据的文本内容
     * @return 剥离前置元数据后的正文内容。如果没有前置元数据，则返回原始内容
     */
    public static String stripFrontmatter(String content) {
        return parseFrontmatter(content).content();
    }

    /**
     * 将字符串中的换行符归一化为 Unix 风格的 \n。
     * <p>
     * 处理顺序：
     * <ol>
     *   <li>先将 Windows 换行符 "\r\n" 替换为 "\n"（需优先处理，避免拆分为两个单独的替换）</li>
     *   <li>再将旧 Mac 换行符 "\r" 替换为 "\n"</li>
     * </ol>
     * 这种先处理 "\r\n" 再处理 "\r" 的顺序是安全的，因为 "\r\n" 中的 "\r" 在第一步已被移除，
     * 后续的 "\r" 替换不会误伤已处理过的 Windows 换行符。
     *
     * @param value 原始字符串，可能包含不同平台的换行符
     * @return 换行符统一为 \n 后的字符串
     */
    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }
}
