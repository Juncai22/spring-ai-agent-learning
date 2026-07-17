package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 部分配置更新数据结构，用于增量更新配置。
 *
 * <p>与 {@link SettingsData} 不同，本类专用于表示"只更新部分字段"的配置变更请求。
 * 所有字段均为可空（nullable），持久化时仅序列化非 null 字段（通过 {@link JsonInclude.Include#NON_NULL} 控制），
 * null 字段表示"不修改此字段"。
 *
 * <p>使用示例：
 * <pre>{@code
 * SettingsUpdate update = SettingsUpdate.builder()
 *     .defaultModel("claude-opus-4-20250514")
 *     .showImages(false)
 *     .build();
 * settingsManager.save(update); // 只修改 defaultModel 和 showImages，其他字段保持不变
 * }</pre>
 *
 * <p>此 Record 的字段结构与 {@link SettingsData} 保持一致，便于通过
 * {@link SettingsManager#applyUpdate(SettingsData, SettingsUpdate)} 方法将部分更新合并到现有配置中。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingsUpdate(
    String defaultProvider,
    String defaultModel,
    String defaultThinkingLevel,
    String transport,
    String steeringMode,
    String followUpMode,
    String theme,
    Boolean showImages,
    Boolean clearOnShrink,
    Boolean autoResize,
    Boolean blockImages,
    CompactionSettings compaction,
    BranchSummarySettings branchSummary,
    RetrySettings retry,
    ThinkingBudgets thinkingBudgets,
    List<String> extensionPaths,
    List<String> skillPaths,
    List<String> promptPaths,
    List<String> themePaths
) {
    /**
     * 创建 Builder 实例，用于构建 SettingsUpdate 对象。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() { return new Builder(); }

    /**
     * SettingsUpdate 的建造者（Builder）类。
     *
     * <p>采用流式 API（fluent API）设计，每个 setter 方法返回 Builder 自身，
     * 支持链式调用。所有字段初始为 null，表示"不修改"。
     */
    public static class Builder {
        private String defaultProvider, defaultModel, defaultThinkingLevel;
        private String transport, steeringMode, followUpMode, theme;
        private Boolean showImages, clearOnShrink, autoResize, blockImages;
        private CompactionSettings compaction;
        private BranchSummarySettings branchSummary;
        private RetrySettings retry;
        private ThinkingBudgets thinkingBudgets;
        private List<String> extensionPaths, skillPaths, promptPaths, themePaths;

        /** 设置默认 AI 提供商 */
        public Builder defaultProvider(String v) { this.defaultProvider = v; return this; }
        /** 设置默认 AI 模型 */
        public Builder defaultModel(String v) { this.defaultModel = v; return this; }
        /** 设置默认思考级别 */
        public Builder defaultThinkingLevel(String v) { this.defaultThinkingLevel = v; return this; }
        /** 设置通信传输方式 */
        public Builder transport(String v) { this.transport = v; return this; }
        /** 设置导向模式 */
        public Builder steeringMode(String v) { this.steeringMode = v; return this; }
        /** 设置跟进模式 */
        public Builder followUpMode(String v) { this.followUpMode = v; return this; }
        /** 设置终端主题 */
        public Builder theme(String v) { this.theme = v; return this; }
        /** 设置是否显示图片 */
        public Builder showImages(Boolean v) { this.showImages = v; return this; }
        /** 设置缩小窗口时是否清除内容 */
        public Builder clearOnShrink(Boolean v) { this.clearOnShrink = v; return this; }
        /** 设置是否自动调整图片尺寸 */
        public Builder autoResize(Boolean v) { this.autoResize = v; return this; }
        /** 设置是否阻止图片加载 */
        public Builder blockImages(Boolean v) { this.blockImages = v; return this; }
        /** 设置上下文压缩配置 */
        public Builder compaction(CompactionSettings v) { this.compaction = v; return this; }
        /** 设置分支摘要配置 */
        public Builder branchSummary(BranchSummarySettings v) { this.branchSummary = v; return this; }
        /** 设置重试策略配置 */
        public Builder retry(RetrySettings v) { this.retry = v; return this; }
        /** 设置各思考级别的 Token 预算 */
        public Builder thinkingBudgets(ThinkingBudgets v) { this.thinkingBudgets = v; return this; }
        /** 设置扩展搜索路径列表 */
        public Builder extensionPaths(List<String> v) { this.extensionPaths = v; return this; }
        /** 设置技能搜索路径列表 */
        public Builder skillPaths(List<String> v) { this.skillPaths = v; return this; }
        /** 设置提示词模板搜索路径列表 */
        public Builder promptPaths(List<String> v) { this.promptPaths = v; return this; }
        /** 设置主题文件搜索路径列表 */
        public Builder themePaths(List<String> v) { this.themePaths = v; return this; }

        /**
         * 构建 SettingsUpdate 实例。
         * 所有未设置的字段保持为 null，表示"不修改"。
         *
         * @return 构建完成的 SettingsUpdate 实例
         */
        public SettingsUpdate build() {
            return new SettingsUpdate(
                defaultProvider, defaultModel, defaultThinkingLevel,
                transport, steeringMode, followUpMode, theme,
                showImages, clearOnShrink, autoResize, blockImages,
                compaction, branchSummary, retry, thinkingBudgets,
                extensionPaths, skillPaths, promptPaths, themePaths
            );
        }
    }
}
