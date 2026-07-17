/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.cloud.ai.demo.entity.Feedback;
import com.alibaba.cloud.ai.demo.entity.Order;
import com.alibaba.cloud.ai.demo.entity.Product;
import com.alibaba.cloud.ai.demo.mapper.FeedbackMapper;
import com.alibaba.cloud.ai.demo.mapper.OrderMapper;
import com.alibaba.cloud.ai.demo.mapper.ProductMapper;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.node.LlmNode;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.util.GsonTool;

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
 * 经营日报 Agent 配置
 * ============================================
 *
 * 【核心作用】
 * 配置一个定时执行的 Agent，自动从数据库读取订单和反馈数据，
 * 生成包含以下内容的经营日报并通过钉钉发送：
 * 1. 经营概览（总销量、总销售额、平均客单价、环比变化）
 * 2. TOP3 热销产品（销量榜 + 营收榜）
 * 3. 口碑表现（好评率、差评率、评分分布）
 * 4. 客户核心诉求与意见反馈
 * 5. 门店运营建议
 *
 * 【Graph 结构】
 *   START
 *     ↓
 *   data_loader（数据加载节点：从数据库查询并计算统计数据）
 *     ↓
 *   data_analysis（LLM 分析节点：根据模板生成日报）
 *     ↓
 *   message_sender（钉钉发送节点：发送日报到钉钉群）
 *     ↓
 *   END
 *
 * 【执行方式】
 * 定时触发（由 XXL-JOB 或 CronTaskParseAgent 调度），
 * 不是由用户请求触发的。这是你之前没学过的模式——Agent 自主定时运行。
 *
 * 【关键设计：data_loader → LLM 的数据传递】
 * data_loader 从数据库查询原始数据，计算好统计指标，存入 State 的 "data_summary" key。
 * data_analysis 节点（LlmNode）通过 paramsKey("data_summary") 读取数据，
 * 填充到日报模板的 {content} 等占位符中，交给 LLM 生成分析文字。
 *
 * 【与 ChatClient 路线的关系】
 * 这个 Agent 使用的是 ChatClient + LlmNode（你模块 15 学的路线），
 * 而不是 ReactAgent。因为这里是"给定数据→按模板生成报告"的确定性流程，
 * 不需要 Agent 自主决策，用 ChatClient 更简洁高效。
 *
 * @author yaohui
 * @create 2025/8/15 15:37
 **/
@Configuration
public class DailyReportAgentConfiguration {

    @Value("${agent.dingtalk.access-token}")
    private String accessToken;

    /**
     * 日报模板
     * 包含多个 {占位符}，由 data_loader 节点计算数据后填充
     * 包含多个 <该区域待分析替换></该区域待分析替换>，由 LLM 填充分析内容
     */
    private static final String DAILY_REPORT = """
            你是一个经营日报助手，能根据用户提供的核心数据信息进行分析总结，并按指定格式生成对应报告。

            用户提供的数据内容如下：
            {content}

            约束
            在如下的报告格式中，相关产品数据金额部分的描述不允许进行修改调整，在如下返回内容中对应的"<该区域待分析替换></该区域待分析替换>"位置，参考其指引方向进行总结分析并完整替换"<该区域待分析替换></该区域待分析替换>"的内容。

            返回内容限定如下：

            # 🏪 门店经营日报 \s
            > 日期：{report_date} \s
            > 店铺名称：**{store_name}** \s
            > 报告生成时间：{report_time}

            ---

            ## 1. 📦 上一日经营概览
            - **总销量（件）**：{total_sales} \s
            - **总销售额（¥）**：{total_revenue} \s
            - **平均客单价（¥）**：{avg_price} \s
            - **环比昨日**：{sales_growth}（销售额） / {order_change}（订单数）

            ---

            ## 2. 🏆 TOP3 热销产品

            - **销量榜**

            1. 🥇 **{product1}** - {product1_quantity}杯（占总销量 {product1_percentage}%） \s
            2. 🥈 **{product2}** - {product2_quantity}杯（占总销量 {product2_percentage}%） \s
            3. 🥉 **{product3}** - {product3_quantity}杯（占总销量 {product3_percentage}%） \s

            - **营收榜**

            4. 🥇 **{r_product1}** - {r_product1_quantity}元（占总营收 {r_product1_percentage}%）
            5. 🥈 **{r_product2}** - {r_product2_quantity}元（占总营收 {r_product2_percentage}%）
            6. 🥉 **{r_product3}** - {r_product3_quantity}元（占总营收 {r_product3_percentage}%）

            > 🔍 **洞察**：<分析区域>根据用户提供信息中的产品销量和销售额TOP3结合产品说明，分析该区域用户的产品喜好偏向，通过营收和销量关系分析出该区域适合的产品定位</分析区域>

            ---

            ## 3. ⭐ 口碑表现
            - 好评率：{positive_rate}  👍 \s
            - 差评率：{negative_rate} 👎 \s
            - 中评率：{neutral_rate}

            📊 **评分分布（5分制）**：

            ★★★★★ {star5_rate}%
            ★★★★ {star4_rate}%
            ★★★ {star3_rate}%
            ★★ {star2_rate}%
            ★ {star1_rate}%

            > 💡 **洞察**：<分析区域>根据用户评价分析主要的产品相关重点问题是什么</分析区域>

            ---

            ## 4. 💬 客户核心诉求 & 意见反馈
            - **强烈诉求**：
              <分析区域>根据用户评价分析主要的产品相关重点问题是什么，控制在3条内</分析区域>
            - **精选客户留言**：
              <分析区域>根据用户评价分析选取两条有助于改善经营的评价意见，控制在3条内</分析区域>

            ---

            ## 5. 📈 门店运营建议（改进方向）
            <分析区域>根据当前返回内容上述信息，按市场经营做出优化改进分析，分模块给出当前门店优化方向，控制在4条以内</分析区域>

            ---

            📌 **备注**：本日报由【智能营运分析系统】自动生成，数据来源 订单 + 客户评价。
            """;

    /**
     * 创建经营日报 Agent
     *
     * @param chatModel       注入的 ChatModel（OpenAI 兼容）
     * @param feedbackMapper   反馈数据 Mapper
     * @param orderMapper      订单数据 Mapper
     * @param productMapper    产品数据 Mapper
     * @return 编译后的 Graph
     *
     * 【为什么用 CompiledGraph 而不是 ReactAgent】
     * 经营日报的生成流程是确定的（加载→分析→发送），不需要 Agent 自主决策。
     * 使用 StateGraph 手写节点和边，更精确地控制流程和数据流。
     */
    @Bean
    public CompiledGraph dailyReportAgent(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            FeedbackMapper feedbackMapper,
            OrderMapper orderMapper,
            ProductMapper productMapper) throws GraphStateException {

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())  // 添加日志 advisor，记录每次 LLM 调用
                .build();

        // ============================================
        // 节点 1：数据加载节点（data_loader）
        // ============================================
        // 职责：从数据库查询订单和反馈数据，计算统计指标
        // 这是一个纯 Java 计算节点（不调用 LLM），使用 node_async 包装
        AsyncNodeAction dataLoaderNode = node_async((state) -> {
            // 获取 XXL-JOB 上下文（分片索引等）
            XxlJobContext xxlJobContext = (XxlJobContext) state.value("xxl-job-context").orElse(null);
            int shardIndex = 0;
            if (xxlJobContext != null) {
                shardIndex = xxlJobContext.getShardIndex();
            }

            // 获取数据时间范围（从最新的数据月份开始）
            String maxMonth = orderMapper.selectMaxCreatedMonth();
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

            // ---- 查询和分析反馈数据 ----
            String content = "";
            List<Feedback> list = feedbackMapper.selectByTimeRange(startTime, endTime);
            List<String> feedbacks = list.stream().map(Feedback::toFormattedString).toList();
            List<Feedback> validFeedbacks = list.stream()
                    .filter(f -> f.getRating() != null)
                    .collect(Collectors.toList());
            content += "用户评价反馈信息：\n" + feedbacks.stream().collect(Collectors.joining("\n"));

            // 计算好评/中评/差评比例
            int totalValidFeedbacks = validFeedbacks.size();
            long positiveCount = validFeedbacks.stream().filter(f -> f.getRating() == 5).count();
            long neutralCount = validFeedbacks.stream().filter(f -> f.getRating() >= 3 && f.getRating() <= 4).count();
            long negativeCount = validFeedbacks.stream().filter(f -> f.getRating() < 3).count();
            double positiveRate = totalValidFeedbacks > 0 ? (positiveCount * 100.0 / totalValidFeedbacks) : 0;
            double neutralRate = totalValidFeedbacks > 0 ? (neutralCount * 100.0 / totalValidFeedbacks) : 0;
            double negativeRate = totalValidFeedbacks > 0 ? (negativeCount * 100.0 / totalValidFeedbacks) : 0;

            // 计算评分分布（1-5 星）
            long[] ratingDistribution = new long[5];
            for (int i = 0; i < 5; i++) {
                final int rating = i + 1;
                ratingDistribution[i] = validFeedbacks.stream()
                        .filter(f -> f.getRating() != null && f.getRating() == rating).count();
            }
            double[] ratingPercentage = new double[5];
            for (int i = 0; i < 5; i++) {
                ratingPercentage[i] = totalValidFeedbacks > 0
                        ? (ratingDistribution[i] * 100.0 / totalValidFeedbacks) : 0;
            }

            // ---- 查询和分析订单数据 ----
            List<Order> todayOrders = orderMapper.findOrdersByTimeRange(startTime, endTime);
            int todayOrderCount = todayOrders.size();
            BigDecimal totalRevenue = todayOrders.stream()
                    .map(Order::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

            // 环比数据（与上一周期比较）
            Date yesterdayStartTime = new Date(startTime.getTime() - (365L * 24 * 60 * 60 * 1000));
            Date yesterdayEndTime = startTime;
            List<Order> yesterdayOrders = orderMapper.findOrdersByTimeRange(yesterdayStartTime, yesterdayEndTime);
            int yesterdayOrderCount = yesterdayOrders.size();
            BigDecimal yesterdayTotalRevenue = yesterdayOrders.stream()
                    .map(Order::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

            // 按产品统计销量和销售额
            Map<Long, Integer> productSalesCountMap = todayOrders.stream()
                    .collect(Collectors.groupingBy(Order::getProductId, Collectors.summingInt(Order::getQuantity)));
            Map<Long, BigDecimal> productSalesRevenueMap = todayOrders.stream()
                    .collect(Collectors.groupingBy(Order::getProductId,
                            Collectors.reducing(BigDecimal.ZERO, Order::getTotalPrice, BigDecimal::add)));

            // TOP3 销量榜
            List<Map.Entry<Long, Integer>> top3BySalesCount = productSalesCountMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                    .limit(3).collect(Collectors.toList());

            // TOP3 营收榜
            List<Map.Entry<Long, BigDecimal>> top3ByRevenue = productSalesRevenueMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                    .limit(3).collect(Collectors.toList());

            // ---- 组装模板数据 ----
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("store_name", "云原生" + (shardIndex + 1) + "号门店");
            templateData.put("feedbacks", feedbacks);
            templateData.put("total_sales", todayOrderCount);
            templateData.put("yesterday_total_sales", yesterdayOrderCount);
            templateData.put("total_revenue", String.format("%.2f", totalRevenue));
            templateData.put("avg_price", totalRevenue
                    .divide(new BigDecimal(todayOrderCount), 2, RoundingMode.HALF_UP).doubleValue());

            // 计算环比增长
            templateData.put("sales_growth",
                    String.format((totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue() >= 0)
                            ? "📈" : "📉" + " %.2f",
                            (totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue())
                                    / yesterdayTotalRevenue.doubleValue() * 100) + "%");
            templateData.put("order_change",
                    String.format((todayOrderCount - yesterdayOrderCount >= 0) ? "📈" : "📉" + "%.2f",
                            (((double) todayOrderCount - (double) yesterdayOrderCount)
                                    / (double) yesterdayOrderCount * 100D)) + "%");

            // 评价统计
            templateData.put("positive_rate", String.format("%.0f", positiveRate) + "%");
            templateData.put("neutral_rate", String.format("%.0f", neutralRate) + "%");
            templateData.put("negative_rate", String.format("%.0f", negativeRate) + "%");

            // 日期时间
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            templateData.put("report_date", LocalDate.now().format(dateFormatter));
            templateData.put("report_time",
                    LocalDate.now().format(dateFormatter) + " " + LocalTime.now().format(timeFormatter));

            // 评分分布
            for (int i = 0; i < 5; i++) {
                templateData.put("star" + (i + 1) + "_rate", String.format("%.0f", ratingPercentage[i]));
            }

            // TOP3 产品数据（营收榜 + 销量榜）
            content += "\n产品销量说明：\n";
            for (int i = 0; i < 3; i++) {
                if (i < top3ByRevenue.size()) {
                    Map.Entry<Long, BigDecimal> entry = top3ByRevenue.get(i);
                    String productName = "Product " + entry.getKey();
                    Product product = null;
                    try {
                        product = productMapper.selectById(entry.getKey());
                        if (product != null && product.getName() != null) productName = product.getName();
                    } catch (Exception e) { /* 产品不存在时使用默认名称 */ }
                    templateData.put("r_product" + (i + 1), productName);
                    templateData.put("r_product" + (i + 1) + "_quantity", String.format("%.2f", entry.getValue()));
                    double percentage = (entry.getValue().doubleValue() * 100.0) / totalRevenue.doubleValue();
                    templateData.put("r_product" + (i + 1) + "_percentage", String.format("%.1f", percentage));
                    content += productName + " 销售额排名第" + (i + 1) + "，销售额为 " + String.format("%.2f", entry.getValue())
                            + "，占比为 " + String.format("%.1f", percentage) + "%"
                            + ", 产品描述：" + (product != null ? product.getDescription() : "") + "\n";
                }
            }
            for (int i = 0; i < 3; i++) {
                if (i < top3BySalesCount.size()) {
                    Map.Entry<Long, Integer> entry = top3BySalesCount.get(i);
                    String productName = "Product " + entry.getKey();
                    Product product = null;
                    try {
                        product = productMapper.selectById(entry.getKey());
                        if (product != null && product.getName() != null) productName = product.getName();
                    } catch (Exception e) { /* 产品不存在时使用默认名称 */ }
                    templateData.put("product" + (i + 1), productName);
                    templateData.put("product" + (i + 1) + "_quantity", entry.getValue());
                    double percentage = (entry.getValue() * 100.0) / todayOrderCount;
                    templateData.put("product" + (i + 1) + "_percentage", String.format("%.1f", percentage));
                    content += productName + " 销售量排名第" + (i + 1) + "，销量为 " + entry.getValue()
                            + "，占比为 " + String.format("%.1f", percentage) + "%"
                            + ", 产品描述：" + (product != null ? product.getDescription() : "") + "\n";
                }
            }
            templateData.put("content", content);

            Map<String, Object> result = new HashMap<>();
            result.put("data_summary", templateData);

            // 从 XXL-JOB 参数中解析 access_token（用于钉钉发送）
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
        // 节点 2：LLM 数据分析节点（data_analysis）
        // ============================================
        // 使用 LlmNode（Spring AI Alibaba 提供的开箱即用 LLM 节点）
        // - paramsKey：从 State 的哪个 key 读取参数（传给模板）
        // - outputKey：LLM 输出写入 State 的哪个 key
        // - userPromptTemplate：用户提示词模板（包含占位符）
        LlmNode llmDataAnalysisNode = LlmNode.builder()
                .chatClient(chatClient)
                .paramsKey("data_summary")            // 从 data_summary 读取模板参数
                .outputKey("summary_message_to_sender") // 输出到钉钉发送节点的输入 key
                .userPromptTemplate(DAILY_REPORT)
                .build();

        // ============================================
        // 节点 3：构建 Graph 并编译
        // ============================================
        StateGraph stateGraph = new StateGraph("OperationAnalysisAgent", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("data_summary", new ReplaceStrategy());
            strategies.put("summary_message_to_sender", new ReplaceStrategy());
            strategies.put("message_sender_result", new ReplaceStrategy());
            strategies.put("access_token", new ReplaceStrategy());
            return strategies;
        }).addNode("data_loader", dataLoaderNode)
                .addNode("data_analysis", node_async(llmDataAnalysisNode))
                .addNode("message_sender", node_async(generateMessageSender()))
                .addEdge(START, "data_loader")
                .addEdge("data_loader", "data_analysis")
                .addEdge("data_analysis", "message_sender")
                .addEdge("message_sender", END);

        CompiledGraph compiledGraph = stateGraph.compile();
        compiledGraph.setMaxIterations(100);  // 设置最大迭代次数，防止死循环
        return compiledGraph;
    }

    /**
     * 创建钉钉消息发送器
     */
    private DingMessageSenderNode generateMessageSender() {
        return DingMessageSenderNode.builder()
                .accessToken(accessToken)
                .accessTokenKey("access_token")
                .messageContentKey("summary_message_to_sender")
                .resultKey("message_sender_result")
                .title("门店经营日报")
                .build();
    }
}