package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 所有会话条目类型的基类密封接口。
 *
 * <p>会话条目形成树形结构，每个条目有 id 和 parentId。
 * 树形结构支持分支和对话历史导航。
 *
 * <p>Jackson 多态序列化通过 "type" 字段进行路由分发，
 * 支持以下条目类型：
 * <ul>
 *   <li>message - 会话消息</li>
 *   <li>thinking_level_change - 思考级别变更</li>
 *   <li>model_change - 模型变更</li>
 *   <li>compaction - 压缩记录</li>
 *   <li>branch_summary - 分支摘要</li>
 *   <li>custom - 自定义条目（不参与 LLM 上下文）</li>
 *   <li>custom_message - 自定义消息条目（参与 LLM 上下文）</li>
 *   <li>label - 标签/书签</li>
 *   <li>session_info - 会话信息</li>
 * </ul>
 *
 * <p>验证需求：1.3-1.11
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SessionMessageEntry.class, name = "message"),
    @JsonSubTypes.Type(value = ThinkingLevelChangeEntry.class, name = "thinking_level_change"),
    @JsonSubTypes.Type(value = ModelChangeEntry.class, name = "model_change"),
    @JsonSubTypes.Type(value = CompactionEntry.class, name = "compaction"),
    @JsonSubTypes.Type(value = BranchSummaryEntry.class, name = "branch_summary"),
    @JsonSubTypes.Type(value = CustomEntry.class, name = "custom"),
    @JsonSubTypes.Type(value = CustomMessageEntry.class, name = "custom_message"),
    @JsonSubTypes.Type(value = LabelEntry.class, name = "label"),
    @JsonSubTypes.Type(value = SessionInfoEntry.class, name = "session_info")
})
public sealed interface SessionEntry permits
        SessionMessageEntry,
        ThinkingLevelChangeEntry,
        ModelChangeEntry,
        CompactionEntry,
        BranchSummaryEntry,
        CustomEntry,
        CustomMessageEntry,
        LabelEntry,
        SessionInfoEntry {

    /**
     * 条目类型鉴别器（如 "message"、"compaction"）。
     */
    String type();

    /**
     * 条目在会话中的唯一标识符。
     */
    String id();

    /**
     * 父条目 ID，如果这是会话头后的第一个条目则为 null。
     */
    String parentId();

    /**
     * 条目创建时间的 ISO 8601 时间戳。
     */
    String timestamp();
}