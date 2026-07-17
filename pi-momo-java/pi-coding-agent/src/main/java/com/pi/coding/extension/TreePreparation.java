package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.coding.session.SessionEntry;

import java.util.List;

/**
 * 会话树导航的准备数据 —— 包含导航目标、需要摘要的条目和自定义选项。
 *
 * <p>当在会话树中导航时，系统需要准备这些数据来确定导航的目标位置、
 * 需要摘要的分支条目以及摘要生成的选项。
 *
 * @param targetId            目标条目标识符，导航到的位置
 * @param oldLeafId           当前叶子条目标识符（可为 null）
 * @param commonAncestorId    新旧叶子之间的共同祖先条目标识符（可为 null）
 * @param entriesToSummarize  需要生成摘要的条目列表
 * @param userWantsSummary    用户是否请求生成摘要
 * @param customInstructions  自定义摘要指令（可为 null）
 * @param replaceInstructions 如果为 true，则 customInstructions 替换默认提示词
 * @param label               附加到分支摘要条目的标签（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreePreparation(
    @JsonProperty("targetId") String targetId,
    @JsonProperty("oldLeafId") String oldLeafId,
    @JsonProperty("commonAncestorId") String commonAncestorId,
    @JsonProperty("entriesToSummarize") List<SessionEntry> entriesToSummarize,
    @JsonProperty("userWantsSummary") boolean userWantsSummary,
    @JsonProperty("customInstructions") String customInstructions,
    @JsonProperty("replaceInstructions") Boolean replaceInstructions,
    @JsonProperty("label") String label
) { }
