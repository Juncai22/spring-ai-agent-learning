package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Git 分支摘要（Branch Summary）设置。
 *
 * <p>当用户在 Git 分支间切换时，系统会为当前分支的对话历史生成摘要，
 * 以便在切换回来后能够快速恢复上下文。此设置控制摘要的生成行为。
 *
 * <p>配置项说明：
 * <ul>
 *   <li><b>reserveTokens</b> — 为分支摘要保留的 Token 数（默认：8000），
 *       用于在切换分支时保存当前分支的关键上下文信息</li>
 *   <li><b>skipPrompt</b> — 是否跳过生成摘要前的确认提示（默认：false），
 *       设为 true 则自动生成摘要，无需用户确认</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BranchSummarySettings(
    @JsonProperty("reserveTokens") Integer reserveTokens,
    @JsonProperty("skipPrompt") Boolean skipPrompt
) {
    /** 默认分支摘要配置：保留 8000 Token，不跳过确认提示 */
    public static final BranchSummarySettings DEFAULT = new BranchSummarySettings(8000, false);

    /** 获取为分支摘要保留的 Token 数，未配置时默认返回 8000 */
    public int getReserveTokens() { return reserveTokens != null ? reserveTokens : 8000; }
    /** 是否跳过生成摘要前的确认提示，未配置时默认返回 false（不跳过） */
    public boolean isSkipPrompt() { return skipPrompt != null ? skipPrompt : false; }
}
