package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 原始配置数据的数据结构，用于 JSON 的序列化与反序列化。
 *
 * <p>使用 Java 14+ 的 Record 类型，所有字段均为可空（nullable），
 * 以支持部分配置文件（即只包含部分字段的 JSON 文件）。
 * 未配置的字段将为 null，调用方需自行处理默认值逻辑。
 *
 * <p>配置优先级（从低到高）：
 * <ol>
 *   <li>全局配置文件（~/.pi/settings.json）</li>
 *   <li>项目配置文件（{cwd}/.pi/settings.json）</li>
 * </ol>
 * 项目配置中的非 null 字段会覆盖全局配置中的对应字段。
 *
 * <p>字段说明：
 * <ul>
 *   <li><b>defaultProvider</b> — 默认 AI 提供商，如 "anthropic"</li>
 *   <li><b>defaultModel</b> — 默认 AI 模型，如 "claude-sonnet-4-20250514"</li>
 *   <li><b>defaultThinkingLevel</b> — 默认思考级别，如 "none"、"low"、"medium"、"high"</li>
 *   <li><b>transport</b> — 通信传输方式，如 "http"、"stdio"</li>
 *   <li><b>steeringMode</b> — 导向模式，控制 AI 回复的引导方式</li>
 *   <li><b>followUpMode</b> — 跟进模式，控制 AI 是否主动发起跟进对话</li>
 *   <li><b>theme</b> — 终端主题名称</li>
 *   <li><b>showImages</b> — 是否在终端中显示图片</li>
 *   <li><b>clearOnShrink</b> — 终端缩小窗口时是否清除内容</li>
 *   <li><b>autoResize</b> — 是否自动调整图片尺寸</li>
 *   <li><b>blockImages</b> — 是否阻止所有图片加载</li>
 *   <li><b>compaction</b> — 上下文压缩设置（嵌套对象）</li>
 *   <li><b>branchSummary</b> — Git 分支摘要设置（嵌套对象）</li>
 *   <li><b>retry</b> — API 重试设置（嵌套对象）</li>
 *   <li><b>thinkingBudgets</b> — 各思考级别的 Token 预算（嵌套对象）</li>
 *   <li><b>extensionPaths</b> — 扩展搜索路径列表</li>
 *   <li><b>skillPaths</b> — 技能搜索路径列表</li>
 *   <li><b>promptPaths</b> — 提示词模板搜索路径列表</li>
 *   <li><b>themePaths</b> — 主题文件搜索路径列表</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettingsData(
    @JsonProperty("defaultProvider") String defaultProvider,
    @JsonProperty("defaultModel") String defaultModel,
    @JsonProperty("defaultThinkingLevel") String defaultThinkingLevel,
    @JsonProperty("transport") String transport,
    @JsonProperty("steeringMode") String steeringMode,
    @JsonProperty("followUpMode") String followUpMode,
    @JsonProperty("theme") String theme,
    @JsonProperty("showImages") Boolean showImages,
    @JsonProperty("clearOnShrink") Boolean clearOnShrink,
    @JsonProperty("autoResize") Boolean autoResize,
    @JsonProperty("blockImages") Boolean blockImages,
    @JsonProperty("compaction") CompactionSettings compaction,
    @JsonProperty("branchSummary") BranchSummarySettings branchSummary,
    @JsonProperty("retry") RetrySettings retry,
    @JsonProperty("thinkingBudgets") ThinkingBudgets thinkingBudgets,
    @JsonProperty("extensionPaths") List<String> extensionPaths,
    @JsonProperty("skillPaths") List<String> skillPaths,
    @JsonProperty("promptPaths") List<String> promptPaths,
    @JsonProperty("themePaths") List<String> themePaths
) {
    /** 空的配置常量，所有字段均为 null，表示使用全默认值 */
    public static final SettingsData EMPTY = new SettingsData(
        null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null,
        null, null, null, null
    );
}
