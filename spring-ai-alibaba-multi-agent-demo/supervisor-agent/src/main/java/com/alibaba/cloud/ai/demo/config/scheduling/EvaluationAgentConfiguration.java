/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.demo.entity.Feedback;
import com.alibaba.cloud.ai.demo.mapper.FeedbackMapper;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.node.IterationNode;
import com.alibaba.cloud.ai.graph.node.LlmNode;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.google.gson.Gson;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.util.GsonTool;
import org.apache.commons.lang3.StringUtils;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * ============================================
 * 用户评价分析 Agent 配置
 * ============================================
 *
 * 【核心作用】
 * 配置一个定时执行的 Agent，自动从数据库读取用户反馈数据，
 * 对每条评价进行 LLM 分类分析（投诉/满意度），汇总后生成分析报告并通过钉钉发送。
 *
 * 【Graph 结构（比 DailyReportAgent 更复杂）】
 *   START
 *     ↓
 *   session_loader_node（加载评价数据）
 *     ↓
 *   iteration_session_analysis_node（★IterationNode：逐个分析每条评价）
 *     │  内部子图 (session_analysis):
 *     │    START → iterator(EvaluationClassifierNode) → END
 *     ↓
 *   session_result_summary_node（汇总分析结果）
 *     ↓
 *   message_parse（LLM 格式化报告）
 *     ↓
 *   message_sender（钉钉发送）
 *     ↓
 *   END
 *
 * 【关键概念：IterationNode（迭代节点）】
 * 这是 Spring AI Alibaba Graph 框架提供的一个特殊节点类型，
 * 用于对数组中的每个元素进行相同的处理。
 *
 * 它的工作方式类似于你学过的 fan-out/fan-in（模块 09），但是是串行的：
 * 1. 从 State 的 inputArrayJsonKey 读取数组
 * 2. 对每个元素，设置到 iteratorItemKey，执行子图
 * 3. 收集每个元素的子图执行结果，存入 outputArrayJsonKey
 *
 * 本例中：
 * - 输入数组：sessions（评价记录列表）
 * - 迭代项：iterator_item（单条评价记录）
 * - 处理节点：EvaluationClassifierNode（LLM 分类分析）
 * - 输出数组：analysis_results（所有分析结果的 JSON 数组）
 *
 * 【与 DailyReportAgent 的区别】
 * | 维度         | DailyReportAgent          | EvaluationAgent              |
 * |-------------|--------------------------|------------------------------|
 * | 数据量       | 全量聚合统计              | 逐条分析（20+ 条评价）        |
 * | 核心节点     | LlmNode（一次 LLM 调用）  | IterationNode（N 次 LLM 调用）|
 * | 输出         | 结构化日报                | 投诉告警 + 满意度分析         |
 * | 复杂度       | 线性流程                  | 迭代 + 子图 + 汇总            |
 *
 * @author yaohui
 * @create 2025/8/15 15:37
 **/
@Configuration
public class EvaluationAgentConfiguration {

    @Value("${agent.dingtalk.access-token}")
    private String accessToken;

    /**
     * 创建评价分析 Agent
     *
     * @param chatModel       注入的 ChatModel
     * @param feedbackMapper  反馈数据 Mapper
     * @return 编译后的 Graph
     */
    @Bean
    public CompiledGraph evaluationAnalysisAgent(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            FeedbackMapper feedbackMapper) throws GraphStateException {

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        // ============================================
        // 步骤 1：构建子图（用于迭代处理每条评价）
        // ============================================
        KeyStrategyFactory subFactory1 = () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            map.put("iterator_item", new ReplaceStrategy());
            map.put("session_analysis_result", new ReplaceStrategy());
            return map;
        };

        // 创建评价分类器节点
        EvaluationClassifierNode sessionAnalysis = EvaluationClassifierNode.builder()
                .chatClient(chatClient)
                .inputTextKey("iterator_item")          // 从迭代项读取评价文本
                .outputKey("session_analysis_result")   // 输出分类结果
                .categories(List.of("yes", "no"))       // complaint 的取值
                .classificationInstructions(List.of(
                        "结果仅需返回JSON字符串，不能有其他不符合JSON格式字符出现，包含字段:user、time、complaint、satisfaction、summary。",
                        "complaint: 表示当前评价是否为店铺或产品投诉，取值范围（yes or no）.",
                        "satisfaction: 表示用户实际的消费满意度",
                        "summary: 提炼本条核心吐槽点，以及可以改进的方向"))
                .build();

        // 构建子图（只包含一个节点：迭代处理）
        StateGraph sessionAnalysisGraph = new StateGraph("session_analysis", subFactory1)
                .addNode("iterator", node_async(sessionAnalysis))
                .addEdge(StateGraph.START, "iterator")
                .addEdge("iterator", StateGraph.END);

        // ============================================
        // 步骤 2：数据加载节点
        // ============================================
        // 从数据库查询反馈数据，转换为字符串列表
        AsyncNodeAction sessionLoaderNode = node_async((state) -> {
            XxlJobContext xxlJobContext = (XxlJobContext) state.value("xxl-job-context").orElse(null);
            String maxMonth = feedbackMapper.selectMaxCreatedMonth();
            Date startTime;
            Date endTime;

            if (maxMonth != null && !maxMonth.isEmpty()) {
                try {
                    YearMonth yearMonth = YearMonth.parse(maxMonth);
                    LocalDate firstDayOfMonth = yearMonth.atDay(1);
                    startTime = Date.from(firstDayOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant());
                } catch (Exception e) {
                    startTime = new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000);
                }
            } else {
                startTime = new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000);
            }
            endTime = new Date();

            List<Feedback> list = feedbackMapper.selectByTimeRange(startTime, endTime);
            List<String> sessionList = list.stream().map(Feedback::toFormattedString).toList();

            Map<String, Object> result = new HashMap<>();
            result.put("sessions", sessionList);  // 存入 sessions 供 IterationNode 读取

            // 从 XXL-JOB 参数中解析 access_token
            if (xxlJobContext != null) {
                try {
                    String accessToken = GsonTool.fromJson(xxlJobContext.getJobParam(), Map.class)
                            .get("access_token").toString();
                    result.put("access_token", accessToken);
                } catch (Exception e) {
                    System.out.println("解析任务参数失败: " + e.getMessage());
                }
            }
            return result;
        });

        // ============================================
        // 步骤 3：结果汇总节点
        // ============================================
        // 从 analysis_results 中读取所有分类结果 JSON，汇总统计
        AsyncNodeAction sessionResultSummaryNode = node_async((state) -> {
            String s = state.value("analysis_results", "[]");
            String message = """
                    用户投诉分析监控
                    总评价记录数: %d条，产品投诉: %d条, 平均满意度(0～5): %d.
                    用户核心诉求：%s
                    """;
            List<String> results = new Gson().fromJson(s, List.class);
            int total = results.size();
            int complaint = 0;
            int satisfaction = 0;
            String require = "";

            if (!results.isEmpty()) {
                for (String result : results) {
                    Map<String, Object> map = new Gson().fromJson(result, Map.class);
                    // 统计投诉
                    if (StringUtils.equals("yes", map.get("complaint").toString())) {
                        complaint++;
                        if (map.containsKey("summary") && map.get("summary") instanceof String) {
                            require += map.get("summary") + "\n";
                        }
                    }
                    // 累加满意度
                    if (map.containsKey("satisfaction") && map.get("satisfaction") instanceof Number) {
                        satisfaction += ((Number) map.get("satisfaction")).intValue();
                    }
                }
                message = String.format(message, total, complaint, (satisfaction / total), require);
                System.out.println(">>" + message);
                return Map.of("summary_message", Map.of("context", message));
            }
            return Map.of();
        });

        // ============================================
        // 步骤 4：LLM 格式化节点
        // ============================================
        // 将汇总数据格式化为钉钉 Markdown 消息
        LlmNode llmNode = LlmNode.builder()
                .chatClient(chatClient)
                .paramsKey("summary_message")
                .outputKey("summary_message_to_sender")
                .systemPromptTemplate("""
                        你是一个告警信息整理助手，需要将用户提供的信息整体适合钉钉发送的Markdown格式，对用户核心诉求后的内容进行压缩提炼总结核心点。

                        核心内容如下：{context}

                        约束：
                        用户提供内容中的数据数值信息绝对不能串改，改进方向如果有则确保控制在3条以内，信息结构内容参考如下格式
                        ## 📊 用户投诉分析监控

                        **📈 总评价记录数**：`%d` 条 \s
                        **⚠️ 产品投诉**：`%d` 条 \s
                        **⭐ 平均满意度 (0～5)**：`%d`

                        ---

                        ### 🔍 用户核心诉求
                        > %s

                        ---

                        ### 🛠️ 改进方向

                    """)
                .build();

        // ============================================
        // 步骤 5：构建 IterationNode（核心：迭代处理每条评价）
        // ============================================
        // IterationNode.converter() 是一个 Builder 模式的转换器
        // 它把子图包装成一个可以迭代处理数组的节点
        StateGraph iterationNode = IterationNode.converter()
                .inputArrayJsonKey("sessions")          // 从哪里读取数组
                .tempIndexKey("iteration_index1")       // 当前迭代索引的临时 key
                .outputArrayJsonKey("analysis_results") // 所有结果收集到哪个 key
                .iteratorItemKey("iterator_item")       // 当前元素存入哪个 key
                .iteratorResultKey("session_analysis_result") // 当前元素结果从哪个 key 读取
                .tempArrayKey("test_temp_array1")       // 临时数组 key
                .tempStartFlagKey("test_temp_start1")   // 开始标志 key
                .tempEndFlagKey("test_temp_end1")       // 结束标志 key
                .subGraph(sessionAnalysisGraph)         // 对每个元素执行的子图
                .convertToStateGraph();                 // 转换为 StateGraph

        // ============================================
        // 步骤 6：组装主图
        // ============================================
        StateGraph stateGraph = new StateGraph("ReviewAnalysisAgent", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("iterator_item", new ReplaceStrategy());
            strategies.put("session_analysis_result", new ReplaceStrategy());
            strategies.put("sessions", new ReplaceStrategy());
            strategies.put("iteration_index1", new ReplaceStrategy());
            strategies.put("analysis_results", new ReplaceStrategy());
            strategies.put("test_temp_array1", new ReplaceStrategy());
            strategies.put("test_temp_start1", new ReplaceStrategy());
            strategies.put("test_temp_end1", new ReplaceStrategy());
            strategies.put("summary_message", new ReplaceStrategy());
            strategies.put("summary_message_to_sender", new ReplaceStrategy());
            strategies.put("message_sender_result", new ReplaceStrategy());
            strategies.put("access_token", new ReplaceStrategy());
            return strategies;
        }).addNode("session_loader_node", sessionLoaderNode)
                .addNode("iteration_session_analysis_node", iterationNode)  // ★ 迭代节点
                .addNode("session_result_summary_node", sessionResultSummaryNode)
                .addNode("message_parse", node_async(llmNode))
                .addNode("message_sender", node_async(generateMessageSender()))
                .addEdge(START, "session_loader_node")
                .addEdge("session_loader_node", "iteration_session_analysis_node")
                .addEdge("iteration_session_analysis_node", "session_result_summary_node")
                .addEdge("session_result_summary_node", "message_parse")
                .addEdge("message_parse", "message_sender")
                .addEdge("message_sender", END);

        CompiledGraph compiledGraph = stateGraph.compile();
        compiledGraph.setMaxIterations(1000);  // 设置更大的最大迭代次数（因为有子图迭代）
        return compiledGraph;
    }

    private DingMessageSenderNode generateMessageSender() {
        return DingMessageSenderNode.builder()
                .accessToken(accessToken)
                .accessTokenKey("access_token")
                .messageContentKey("summary_message_to_sender")
                .resultKey("message_sender_result")
                .title("用户投诉分析监控")
                .build();
    }
}