package com.pi.coding.tool;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Edit 工具的差异计算工具类：提供文本模糊匹配、行尾检测和 Unified Diff 生成功能。
 *
 * <p>该类是一个工具类（不可实例化），包含一系列静态方法，用于：
 * <ul>
 *   <li>模糊匹配：在文本中查找目标字符串，支持精确匹配和模糊匹配两种模式</li>
 *   <li>行尾处理：自动检测文件的行尾风格（CRLF/LF），并在替换后还原</li>
 *   <li>Unicode 规范化：将智能引号、破折号、特殊空格等 Unicode 字符规范化为 ASCII 等价物</li>
 *   <li>差异生成：基于 LCS（最长公共子序列）算法生成逐行 Unified Diff</li>
 *   <li>BOM 处理：检测和剥离 UTF-8 BOM（字节顺序标记）</li>
 * </ul>
 */
public final class EditDiff {

    private EditDiff() {
        // \u5DE5\u5177\u7C7B\uFF0C\u7981\u6B62\u5B9E\u4F8B\u5316
    }

    /**
     * \u68C0\u6D4B\u6587\u672C\u5185\u5BB9\u7684\u884C\u5C3E\u98CE\u683C\u3002
     * <p>
     * \u901A\u8FC7\u67E5\u627E\u7B2C\u4E00\u4E2A\u6362\u884C\u7B26\u6765\u5224\u65AD\u662F Windows \u98CE\u683C\uFF08CRLF\uFF09\u8FD8\u662F Unix \u98CE\u683C\uFF08LF\uFF09\u3002
     * \u5982\u679C\u6587\u672C\u4E2D\u540C\u65F6\u5305\u542B\u4E24\u79CD\u98CE\u683C\uFF0C\u4EE5\u5148\u51FA\u73B0\u7684\u4E3A\u51C6\u3002
     *
     * @param content \u8981\u5206\u6790\u7684\u6587\u672C\u5185\u5BB9
     * @return Windows \u98CE\u683C\u8FD4\u56DE {@code "\r\n"}\uFF0CUnix \u98CE\u683C\u8FD4\u56DE {@code "\n"}
     */
    public static String detectLineEnding(String content) {
        int crlfIdx = content.indexOf("\r\n");
        int lfIdx = content.indexOf("\n");
        if (lfIdx == -1) return "\n";
        if (crlfIdx == -1) return "\n";
        return crlfIdx < lfIdx ? "\r\n" : "\n";
    }

    /**
     * \u5C06\u6240\u6709\u6362\u884C\u7B26\u7EDF\u4E00\u89C4\u8303\u5316\u4E3A LF\uFF08Unix \u98CE\u683C\uFF09\u3002
     * <p>
     * \u4F9D\u6B21\u66FF\u6362 CRLF \u548C\u5355\u72EC\u7684 CR \u4E3A LF\uFF0C\u786E\u4FDD\u540E\u7EED\u5904\u7406\u65F6\u6362\u884C\u7B26\u4E00\u81F4\u3002
     *
     * @param text \u8981\u89C4\u8303\u5316\u7684\u6587\u672C
     * @return \u6362\u884C\u7B26\u7EDF\u4E00\u4E3A LF \u540E\u7684\u6587\u672C
     */
    public static String normalizeToLF(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * \u5C06\u6362\u884C\u7B26\u8FD8\u539F\u4E3A\u6307\u5B9A\u7684\u884C\u5C3E\u98CE\u683C\u3002
     * <p>
     * \u5728\u5B8C\u6210\u6240\u6709\u6587\u672C\u5904\u7406\u64CD\u4F5C\u540E\u8C03\u7528\uFF0C\u786E\u4FDD\u6587\u4EF6\u7684\u539F\u59CB\u884C\u5C3E\u98CE\u683C\u5F97\u4EE5\u4FDD\u7559\u3002
     *
     * @param text \u8981\u5904\u7406\u7684\u6587\u672C\uFF08\u5F53\u524D\u4E3A LF \u98CE\u683C\uFF09
     * @param ending \u76EE\u6807\u884C\u5C3E\u98CE\u683C\uFF08{@code "\r\n"} \u6216 {@code "\n"}\uFF09
     * @return \u8FD8\u539F\u884C\u5C3E\u98CE\u683C\u540E\u7684\u6587\u672C
     */
    public static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    // Unicode \u89C4\u8303\u5316\u6B63\u5219\u8868\u8FBE\u5F0F\u6A21\u5F0F
    /** \u5339\u914D\u5404\u79CD\u667A\u80FD\u5355\u5F15\u53F7\u5B57\u7B26\uFF08\u5F2F\u5F15\u53F7\uFF09 */
    private static final Pattern SMART_SINGLE_QUOTES = Pattern.compile("[\u2018\u2019\u201A\u201B]");
    /** \u5339\u914D\u5404\u79CD\u667A\u80FD\u53CC\u5F15\u53F7\u5B57\u7B26\uFF08\u5F2F\u5F15\u53F7\uFF09 */
    private static final Pattern SMART_DOUBLE_QUOTES = Pattern.compile("[\u201C\u201D\u201E\u201F]");
    /** \u5339\u914D\u5404\u79CD Unicode \u7834\u6298\u53F7\u548C\u8FDE\u5B57\u7B26 */
    private static final Pattern DASHES = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]");
    /** \u5339\u914D\u5404\u79CD\u7279\u6B8A\u7A7A\u683C\u5B57\u7B26\uFF08\u975E\u65AD\u884C\u7A7A\u683C\u3001\u7A84\u7A7A\u683C\u7B49\uFF09 */
    private static final Pattern SPECIAL_SPACES = Pattern.compile("[\u00A0\u2002-\u200A\u202F\u205F\u3000]");

    /**
     * \u5BF9\u6587\u672C\u8FDB\u884C\u6A21\u7CCA\u5339\u914D\u89C4\u8303\u5316\u5904\u7406\u3002
     * <p>
     * \u5E94\u7528\u6E10\u8FDB\u5F0F\u8F6C\u6362\uFF0C\u4F7F\u4E0D\u540C Unicode \u53D8\u4F53\u7684\u6587\u672C\u4ECD\u80FD\u5339\u914D\u6210\u529F\uFF1A
     * <ol>
     *   <li>NFKC Unicode \u89C4\u8303\u5316\uFF08\u5206\u89E3\u5E76\u91CD\u7EC4\u590D\u5408\u5B57\u7B26\uFF09</li>
     *   <li>\u53BB\u9664\u6BCF\u884C\u672B\u5C3E\u7684\u7A7A\u767D\u5B57\u7B26</li>
     *   <li>\u667A\u80FD\u5355\u5F15\u53F7 \u2192 ASCII \u5355\u5F15\u53F7\uFF08'\uFF09</li>
     *   <li>\u667A\u80FD\u53CC\u5F15\u53F7 \u2192 ASCII \u53CC\u5F15\u53F7\uFF08"\uFF09</li>
     *   <li>\u5404\u79CD Unicode \u7834\u6298\u53F7 \u2192 ASCII \u8FDE\u5B57\u7B26\uFF08-\uFF09</li>
     *   <li>\u7279\u6B8A\u7A7A\u683C \u2192 \u666E\u901A\u7A7A\u683C\uFF08 \uFF09</li>
     * </ol>
     *
     * @param text \u8981\u89C4\u8303\u5316\u7684\u6587\u672C
     * @return \u89C4\u8303\u5316\u540E\u7684\u6587\u672C\uFF0C\u9002\u5408\u8FDB\u884C\u6A21\u7CCA\u5339\u914D
     */
    public static String normalizeForFuzzyMatch(String text) {
        // NFKC normalization
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);

        // Strip trailing whitespace per line
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(stripTrailingWhitespace(lines[i]));
        }
        normalized = sb.toString();

        // Smart single quotes → '
        normalized = SMART_SINGLE_QUOTES.matcher(normalized).replaceAll("'");
        // Smart double quotes → "
        normalized = SMART_DOUBLE_QUOTES.matcher(normalized).replaceAll("\"");
        // Various dashes/hyphens → -
        normalized = DASHES.matcher(normalized).replaceAll("-");
        // Special spaces → regular space
        normalized = SPECIAL_SPACES.matcher(normalized).replaceAll(" ");

        return normalized;
    }

    /**
     * \u53BB\u9664\u5B57\u7B26\u4E32\u672B\u5C3E\u7684\u7A7A\u767D\u5B57\u7B26\u3002
     * <p>
     * \u7528\u4E8E\u6A21\u7CCA\u5339\u914D\u524D\u7684\u89C4\u8303\u5316\u5904\u7406\uFF0C\u786E\u4FDD\u56E0\u884C\u5C3E\u7A7A\u683C\u5DEE\u5F02\u5BFC\u81F4\u7684\u5339\u914D\u5931\u8D25\u3002
     *
     * @param line \u8981\u5904\u7406\u7684\u5B57\u7B26\u4E32\u884C
     * @return \u53BB\u9664\u672B\u5C3E\u7A7A\u767D\u540E\u7684\u5B57\u7B26\u4E32
     */
    private static String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * \u6A21\u7CCA\u6587\u672C\u5339\u914D\u7ED3\u679C\u8BB0\u5F55\u3002
     * <p>
     * \u5305\u542B\u5339\u914D\u662F\u5426\u6210\u529F\u3001\u5339\u914D\u4F4D\u7F6E\u3001\u5339\u914D\u957F\u5EA6\u3001\u662F\u5426\u4F7F\u7528\u4E86\u6A21\u7CCA\u5339\u914D\u7B49\u4FE1\u606F\u3002
     * \u63D0\u4F9B\u4E09\u4E2A\u5DE5\u5382\u65B9\u6CD5\u521B\u5EFA\u4E0D\u540C\u5339\u914D\u7ED3\u679C\u3002
     *
     * @param found \u662F\u5426\u627E\u5230\u5339\u914D
     * @param index \u5339\u914D\u4F4D\u7F6E\u5728\u539F\u6587\u672C\u4E2D\u7684\u7D22\u5F15
     * @param matchLength \u5339\u914D\u6587\u672C\u7684\u957F\u5EA6
     * @param usedFuzzyMatch \u662F\u5426\u4F7F\u7528\u4E86\u6A21\u7CCA\u5339\u914D\uFF08\u800C\u4E0D\u662F\u7CBE\u786E\u5339\u914D\uFF09
     * @param contentForReplacement \u7528\u4E8E\u6267\u884C\u66FF\u6362\u64CD\u4F5C\u7684\u6587\u672C\u5185\u5BB9
     */
    public record FuzzyMatchResult(
        boolean found,
        int index,
        int matchLength,
        boolean usedFuzzyMatch,
        String contentForReplacement
    ) {
        /**
         * \u521B\u5EFA\u672A\u627E\u5230\u5339\u914D\u7684\u7ED3\u679C\u3002
         *
         * @param content \u539F\u59CB\u6587\u672C\u5185\u5BB9\uFF0C\u7528\u4E8E\u540E\u7EED\u5904\u7406
         * @return \u672A\u5339\u914D\u7684\u7ED3\u679C
         */
        public static FuzzyMatchResult notFound(String content) {
            return new FuzzyMatchResult(false, -1, 0, false, content);
        }

        /**
         * \u521B\u5EFA\u7CBE\u786E\u5339\u914D\u7684\u7ED3\u679C\u3002
         *
         * @param index \u5339\u914D\u4F4D\u7F6E
         * @param length \u5339\u914D\u957F\u5EA6
         * @param content \u539F\u59CB\u6587\u672C\u5185\u5BB9
         * @return \u7CBE\u786E\u5339\u914D\u7ED3\u679C
         */
        public static FuzzyMatchResult exactMatch(int index, int length, String content) {
            return new FuzzyMatchResult(true, index, length, false, content);
        }

        /**
         * \u521B\u5EFA\u6A21\u7CCA\u5339\u914D\u6210\u529F\u7684\u7ED3\u679C\u3002
         *
         * @param index \u5339\u914D\u4F4D\u7F6E\uFF08\u5728\u89C4\u8303\u5316\u540E\u7684\u6587\u672C\u4E2D\u7684\u7D22\u5F15\uFF09
         * @param length \u5339\u914D\u957F\u5EA6\uFF08\u5728\u89C4\u8303\u5316\u540E\u7684\u6587\u672C\u4E2D\u7684\u957F\u5EA6\uFF09
         * @param normalizedContent \u89C4\u8303\u5316\u540E\u7684\u6587\u672C\u5185\u5BB9\uFF0C\u7528\u4E8E\u6267\u884C\u66FF\u6362
         * @return \u6A21\u7CCA\u5339\u914D\u7ED3\u679C
         */
        public static FuzzyMatchResult fuzzyMatch(int index, int length, String normalizedContent) {
            return new FuzzyMatchResult(true, index, length, true, normalizedContent);
        }
    }

    /**
     * \u5728\u6587\u672C\u5185\u5BB9\u4E2D\u67E5\u627E\u76EE\u6807\u6587\u672C\uFF0C\u5148\u5C1D\u8BD5\u7CBE\u786E\u5339\u914D\uFF0C\u5931\u8D25\u540E\u964D\u7EA7\u4E3A\u6A21\u7CCA\u5339\u914D\u3002
     * <p>
     * \u5339\u914D\u7B56\u7565\uFF1A
     * <ol>
     *   <li>\u9996\u5148\u4F7F\u7528 {@link String#indexOf(String)} \u8FDB\u884C\u7CBE\u786E\u5339\u914D</li>
     *   <li>\u5982\u679C\u7CBE\u786E\u5339\u914D\u5931\u8D25\uFF0C\u5BF9\u6587\u672C\u8FDB\u884C Unicode \u89C4\u8303\u5316\u540E\u518D\u5339\u914D</li>
     *   <li>\u5982\u679C\u4ECD\u7136\u5931\u8D25\uFF0C\u8FD4\u56DE\u672A\u627E\u5230\u7ED3\u679C</li>
     * </ol>
     *
     * @param content \u8981\u641C\u7D22\u7684\u6587\u672C\u5185\u5BB9
     * @param oldText \u8981\u67E5\u627E\u7684\u76EE\u6807\u6587\u672C
     * @return \u5339\u914D\u7ED3\u679C\uFF0C\u5305\u542B\u5339\u914D\u4F4D\u7F6E\u3001\u957F\u5EA6\u548C\u662F\u5426\u4F7F\u7528\u4E86\u6A21\u7CCA\u5339\u914D
     */
    public static FuzzyMatchResult fuzzyFindText(String content, String oldText) {
        // \u5148\u5C1D\u8BD5\u7CBE\u786E\u5339\u914D
        int exactIndex = content.indexOf(oldText);
        if (exactIndex != -1) {
            return FuzzyMatchResult.exactMatch(exactIndex, oldText.length(), content);
        }

        // \u7CBE\u786E\u5339\u914D\u5931\u8D25\uFF0C\u964D\u7EA7\u4E3A\u6A21\u7CCA\u5339\u914D
        String fuzzyContent = normalizeForFuzzyMatch(content);
        String fuzzyOldText = normalizeForFuzzyMatch(oldText);
        int fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText);

        if (fuzzyIndex == -1) {
            return FuzzyMatchResult.notFound(content);
        }

        return FuzzyMatchResult.fuzzyMatch(fuzzyIndex, fuzzyOldText.length(), fuzzyContent);
    }

    /**
     * \u68C0\u6D4B\u5E76\u5265\u79BB UTF-8 BOM\uFF08\u5B57\u8282\u987A\u5E8F\u6807\u8BB0\uFF0CU+FEFF\uFF09\u3002
     * <p>
     * \u67D0\u4E9B\u7F16\u8F91\u5668\u4F1A\u5728 UTF-8 \u6587\u4EF6\u5F00\u5934\u5199\u5165 BOM\uFF0C\u8FD9\u4F1A\u5F71\u54CD\u6587\u672C\u5339\u914D\u3002
     * \u6B64\u65B9\u6CD5\u5C06 BOM \u5206\u79BB\u51FA\u6765\uFF0C\u5339\u914D\u64CD\u4F5C\u5728\u53BB BOM \u540E\u7684\u6587\u672C\u4E0A\u8FDB\u884C\uFF0C
     * \u66FF\u6362\u540E\u518D\u5C06 BOM \u91CD\u65B0\u62FC\u63A5\u56DE\u53BB\u3002
     *
     * @param content \u8981\u5904\u7406\u7684\u6587\u672C\u5185\u5BB9
     * @return BOM \u5206\u79BB\u7ED3\u679C\uFF0C\u5305\u542B BOM \u5B57\u7B26\u4E32\u548C\u7EAF\u51C0\u6587\u672C
     */
    public static BomResult stripBom(String content) {
        if (content.startsWith("\uFEFF")) {
            return new BomResult("\uFEFF", content.substring(1));
        }
        return new BomResult("", content);
    }

    /**
     * BOM \u5265\u79BB\u7ED3\u679C\u8BB0\u5F55\u3002
     * <p>
     * \u5C06\u539F\u59CB\u6587\u672C\u4E2D\u7684 BOM\uFF08\u5982\u679C\u6709\uFF09\u548C\u53BB\u9664 BOM \u540E\u7684\u6587\u672C\u5206\u5F00\u5B58\u50A8\uFF0C
     * \u4EE5\u4FBF\u5728\u66FF\u6362\u64CD\u4F5C\u5B8C\u6210\u540E\u80FD\u6B63\u786E\u8FD8\u539F BOM\u3002
     *
     * @param bom \u4ECE\u6587\u672C\u4E2D\u5265\u79BB\u51FA\u7684 BOM \u5B57\u7B26\u4E32\uFF08\u5982\u679C\u6709\u7684\u8BDD\uFF0C\u5426\u5219\u4E3A\u7A7A\u5B57\u7B26\u4E32\uFF09
     * @param text \u53BB\u9664 BOM \u540E\u7684\u7EAF\u51C0\u6587\u672C
     */
    public record BomResult(String bom, String text) {}

    /**
     * 生成 Unified Diff 格式的差异字符串（默认 4 行上下文）。
     * <p>
     * 基于 LCS 算法计算逐行差异，输出格式为：
     * <ul>
     *   <li>{@code +行号 内容} - 新增的行</li>
     *   <li>{@code -行号 内容} - 删除的行</li>
     *   <li>{@code  行号 内容} - 未变更的上下文行</li>
     *   <li>{@code  行号 ...} - 省略的上下文行（用于截断过长上下文）</li>
     * </ul>
     *
     * @param oldContent 原始内容
     * @param newContent 新内容
     * @return Diff 结果，包含差异字符串和第一个变更行号
     */
    public static DiffResult generateDiffString(String oldContent, String newContent) {
        return generateDiffString(oldContent, newContent, 4);
    }

    /**
     * 生成 Unified Diff 格式的差异字符串（自定义上下文行数）。
     * <p>
     * 该实现使用 LCS（最长公共子序列）算法进行逐行比较，
     * 并智能地控制上下文行数：在变更前后显示指定数量的上下文行，
     * 中间用 "..." 省略号截断以避免输出过长。
     *
     * @param oldContent 原始内容
     * @param newContent 新内容
     * @param contextLines 在变更前后显示的行数
     * @return Diff 结果，包含差异字符串和第一个变更行号
     */
    public static DiffResult generateDiffString(String oldContent, String newContent, int contextLines) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        // Simple line-by-line diff
        List<DiffPart> parts = computeLineDiff(oldLines, newLines);

        List<String> output = new ArrayList<>();
        int maxLineNum = Math.max(oldLines.length, newLines.length);
        int lineNumWidth = String.valueOf(maxLineNum).length();

        int oldLineNum = 1;
        int newLineNum = 1;
        boolean lastWasChange = false;
        Integer firstChangedLine = null;

        for (int i = 0; i < parts.size(); i++) {
            DiffPart part = parts.get(i);
            String[] lines = part.lines();

            if (part.added() || part.removed()) {
                // Capture the first changed line
                if (firstChangedLine == null) {
                    firstChangedLine = newLineNum;
                }

                for (String line : lines) {
                    if (part.added()) {
                        String lineNum = padLeft(String.valueOf(newLineNum), lineNumWidth);
                        output.add("+" + lineNum + " " + line);
                        newLineNum++;
                    } else {
                        String lineNum = padLeft(String.valueOf(oldLineNum), lineNumWidth);
                        output.add("-" + lineNum + " " + line);
                        oldLineNum++;
                    }
                }
                lastWasChange = true;
            } else {
                // Context lines
                boolean nextPartIsChange = i < parts.size() - 1 && 
                    (parts.get(i + 1).added() || parts.get(i + 1).removed());

                if (lastWasChange || nextPartIsChange) {
                    String[] linesToShow = lines;
                    int skipStart = 0;
                    int skipEnd = 0;

                    if (!lastWasChange) {
                        skipStart = Math.max(0, lines.length - contextLines);
                        linesToShow = copyOfRange(lines, skipStart, lines.length);
                    }

                    if (!nextPartIsChange && linesToShow.length > contextLines) {
                        skipEnd = linesToShow.length - contextLines;
                        linesToShow = copyOfRange(linesToShow, 0, contextLines);
                    }

                    if (skipStart > 0) {
                        output.add(" " + padLeft("", lineNumWidth) + " ...");
                        oldLineNum += skipStart;
                        newLineNum += skipStart;
                    }

                    for (String line : linesToShow) {
                        String lineNum = padLeft(String.valueOf(oldLineNum), lineNumWidth);
                        output.add(" " + lineNum + " " + line);
                        oldLineNum++;
                        newLineNum++;
                    }

                    if (skipEnd > 0) {
                        output.add(" " + padLeft("", lineNumWidth) + " ...");
                        oldLineNum += skipEnd;
                        newLineNum += skipEnd;
                    }
                } else {
                    oldLineNum += lines.length;
                    newLineNum += lines.length;
                }

                lastWasChange = false;
            }
        }

        return new DiffResult(String.join("\n", output), firstChangedLine != null ? firstChangedLine : 1);
    }

    /**
     * Diff 生成结果记录。
     * <p>
     * 包含 Unified Diff 格式的差异字符串，以及第一个变更行的行号，
     * 便于编辑器定位和导航到变更位置。
     *
     * @param diff Unified Diff 格式的差异字符串
     * @param firstChangedLine 第一个变更行的行号（1-索引）
     */
    public record DiffResult(String diff, int firstChangedLine) {}

    /**
     * Diff 的一部分：表示一组行（新增、删除或未变更）。
     * <p>
     * added 和 removed 不会同时为 true，如果两者均为 false 则表示未变更的上下文行。
     *
     * @param lines 行内容数组
     * @param added 是否为新增行
     * @param removed 是否为删除行
     */
    private record DiffPart(String[] lines, boolean added, boolean removed) {}

    /**
     * 使用 LCS（最长公共子序列）算法计算逐行差异。
     * <p>
     * 算法步骤：
     * <ol>
     *   <li>构建 LCS 动态规划表</li>
     *   <li>回溯表格，找出差异部分</li>
     *   <li>将连续的增/删/未变更行合并为 DiffPart</li>
     * </ol>
     * 时间复杂度：O(m*n)，其中 m 和 n 分别为新旧内容的行数。
     *
     * @param oldLines 原始内容的行数组
     * @param newLines 新内容的行数组
     * @return DiffPart 列表，按顺序排列
     */
    private static List<DiffPart> computeLineDiff(String[] oldLines, String[] newLines) {
        // Use a simple LCS-based diff
        int[][] lcs = computeLCS(oldLines, newLines);
        List<DiffPart> parts = new ArrayList<>();

        int i = oldLines.length;
        int j = newLines.length;
        List<String> currentRemoved = new ArrayList<>();
        List<String> currentAdded = new ArrayList<>();
        List<String> currentUnchanged = new ArrayList<>();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                // Flush any pending changes
                flushChanges(parts, currentRemoved, currentAdded, currentUnchanged);
                currentUnchanged.add(0, oldLines[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                // Flush unchanged
                if (!currentUnchanged.isEmpty()) {
                    parts.add(0, new DiffPart(currentUnchanged.toArray(new String[0]), false, false));
                    currentUnchanged.clear();
                }
                currentAdded.add(0, newLines[j - 1]);
                j--;
            } else if (i > 0) {
                // Flush unchanged
                if (!currentUnchanged.isEmpty()) {
                    parts.add(0, new DiffPart(currentUnchanged.toArray(new String[0]), false, false));
                    currentUnchanged.clear();
                }
                currentRemoved.add(0, oldLines[i - 1]);
                i--;
            }
        }

        flushChanges(parts, currentRemoved, currentAdded, currentUnchanged);

        return parts;
    }

    /**
     * 将缓存的变更行刷新到结果列表中。
     * <p>
     * 刷新顺序为：未变更行 → 新增行 → 删除行，确保在结果列表中
     * 同一位置先显示上下文行，再显示新增行，最后显示删除行。
     * 刷新后清空对应的缓存列表。
     *
     * @param parts 结果列表
     * @param removed 缓存的删除行列表
     * @param added 缓存的新增行列表
     * @param unchanged 缓存的未变更行列表
     */
    private static void flushChanges(List<DiffPart> parts,
            List<String> removed, List<String> added, List<String> unchanged) {
        if (!unchanged.isEmpty()) {
            parts.add(0, new DiffPart(unchanged.toArray(new String[0]), false, false));
            unchanged.clear();
        }
        if (!added.isEmpty()) {
            parts.add(0, new DiffPart(added.toArray(new String[0]), true, false));
            added.clear();
        }
        if (!removed.isEmpty()) {
            parts.add(0, new DiffPart(removed.toArray(new String[0]), false, true));
            removed.clear();
        }
    }

    /**
     * 计算两个字符串数组的 LCS（最长公共子序列）动态规划表。
     * <p>
     * 使用标准 DP 算法：dp[i][j] 表示 a[0..i-1] 和 b[0..j-1] 的 LCS 长度。
     * 如果 a[i-1] == b[j-1]，则 dp[i][j] = dp[i-1][j-1] + 1；
     * 否则 dp[i][j] = max(dp[i-1][j], dp[i][j-1])。
     *
     * @param a 第一个字符串数组
     * @param b 第二个字符串数组
     * @return (m+1) x (n+1) 的 DP 表
     */
    private static int[][] computeLCS(String[] a, String[] b) {
        int m = a.length;
        int n = b.length;
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        return dp;
    }

    /**
     * 左对齐填充字符串到指定宽度（右侧补空格）。
     * <p>
     * 用于对齐 Diff 输出中的行号，使输出格式整齐。
     *
     * @param s 原始字符串
     * @param width 目标宽度
     * @return 左对齐填充后的字符串
     */
    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * 复制数组的指定范围。
     * <p>
     * 类似于 Arrays.copyOfRange，用于处理 Diff 上下文行截断。
     *
     * @param arr 原始数组
     * @param from 起始索引（包含）
     * @param to 结束索引（不包含）
     * @return 新数组，包含指定范围的元素
     */
    private static String[] copyOfRange(String[] arr, int from, int to) {
        String[] result = new String[to - from];
        System.arraycopy(arr, from, result, 0, to - from);
        return result;
    }
}
