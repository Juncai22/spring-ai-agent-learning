/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.util.StringUtils;

/**
 * ============================================
 * 评价分类器（Graph 节点）
 * ============================================
 *
 * 【核心作用】
 * 作为 Graph 中的一个节点，使用 LLM 对每条用户评价进行分类分析。
 * 分析内容包括：
 * 1. 是否为产品/服务投诉（complaint: yes/no）
 * 2. 用户满意度评分（satisfaction: 0~5）
 * 3. 核心吐槽点总结和改进建议（summary）
 *
 * 【在 IterationNode 中的使用】
 * 这个节点被 IterationNode 的子图使用，IterationNode 会遍历所有评价记录，
 * 对每条记录都调用这个节点进行分类分析，最后汇总所有分析结果。
 *
 * 【与简单分类的区别】
 * 这不是简单的关键词匹配分类，而是使用 LLM 进行语义理解：
 * - 能识别隐式投诉（如"等了半小时"→ 投诉）
 * - 能感知情绪强度（如"非常生气"→ 低满意度）
 * - 能提取改进方向（如"建议增加人手"→ 运营建议）
 */
public class EvaluationClassifierNode implements NodeAction {

    /**
     * 分类提示词模板
     * 告诉 LLM 它的角色、任务、输出格式和约束
     */
    private static final String CLASSIFIER_PROMPT_TEMPLATE = """
            ### Job Description
            你是一个用户评价智能分析智能助手.
            ### Task
            对用户的评价记录，你需要完成以下两项信息分析：
            1、判断是否为产品投诉：根据评价内容分析判断是否为原料质量或店员失误导致的产品投诉。
            2、客户情绪状态分析：从客户回复语气判断客户满意度，如果存在情绪激动、抱怨、不耐烦等情况，情绪越强烈分值越低，分值范围：0～5.
            ### Format
            The conversation is: {inputText}. Categories are specified as a category list: {categories}. Satisfaction value is number from 0 to 5.
            Classification instructions may be included to improve the classification accuracy: {classificationInstructions}.
            ### Constraint
            输出JSON字符串，不要包含markdown相关字符。DO NOT include anything other than the JSON string in your response. 输出信息参考如下：
            \\{'user':'10000', 'time': '2025-09-02 14:15:42', 'complaint':'yes', 'satisfaction':1, 'summary':'产品问题'\\}.
            """;

    private SystemPromptTemplate systemPromptTemplate;
    private ChatClient chatClient;
    private String inputText;
    private List<String> categories;
    private List<String> classificationInstructions;
    private String inputTextKey;
    private String outputKey;

    public EvaluationClassifierNode(ChatClient chatClient, String inputTextKey,
                                     List<String> categories, List<String> classificationInstructions,
                                     String outputKey) {
        this.chatClient = chatClient;
        this.inputTextKey = inputTextKey;
        this.categories = categories;
        this.classificationInstructions = classificationInstructions;
        this.systemPromptTemplate = new SystemPromptTemplate(CLASSIFIER_PROMPT_TEMPLATE);
        this.outputKey = outputKey;
    }

    /**
     * Graph 节点执行方法
     *
     * 从 State 中读取一条评价记录，交给 LLM 分析，返回分类结果 JSON。
     *
     * @param state Graph 的共享状态
     * @return 包含分类结果 JSON 的 Map（key 为 outputKey）
     */
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 从 State 中读取输入文本
        if (StringUtils.hasLength(inputTextKey)) {
            this.inputText = (String) state.value(inputTextKey).orElse(this.inputText);
        }

        // 使用 ChatClient 调用 LLM 进行分类
        ChatResponse response = chatClient.prompt()
                .system(systemPromptTemplate.render(Map.of(
                        "inputText", inputText,
                        "categories", categories,
                        "classificationInstructions", classificationInstructions)))
                .user(inputText)
                .call()
                .chatResponse();

        Map<String, Object> updatedState = new HashMap<>();
        updatedState.put(outputKey, response.getResult().getOutput().getText());
        System.out.println(">>" + response.getResult().getOutput().getText());

        // 如果有 messages，也更新（保持消息历史）
        if (state.value("messages").isPresent()) {
            updatedState.put("messages", response.getResult().getOutput());
        }

        return updatedState;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String inputTextKey;
        private ChatClient chatClient;
        private List<String> categories;
        private List<String> classificationInstructions;
        private String outputKey;

        public Builder inputTextKey(String input)           { this.inputTextKey = input; return this; }
        public Builder chatClient(ChatClient chatClient)    { this.chatClient = chatClient; return this; }
        public Builder categories(List<String> categories)  { this.categories = categories; return this; }
        public Builder classificationInstructions(List<String> instructions) { this.classificationInstructions = instructions; return this; }
        public Builder outputKey(String outputKey)          { this.outputKey = outputKey; return this; }

        public EvaluationClassifierNode build() {
            return new EvaluationClassifierNode(chatClient, inputTextKey, categories,
                    classificationInstructions, outputKey);
        }
    }
}