package com.cloud.alibaba.ai.example.controller;

// Note 1: ★★★ AgentController 是 ReactAgent 的 Web 入口, 演示「人工审批」的完整两步流程。
//
// 因为 FileWriteTool 配置了需要审批, Agent 写文件前会暂停。所以调用流程是:
//   第一步 /invoke:  用户提问 → Agent 跑到「要写文件」暂停 → 返回待审批的工具调用清单
//   第二步 /feedback: 用户对每个工具调用批准/拒绝 → Agent 根据反馈继续执行 (写或不写)
//
// threadId 贯穿两步, 用来在 MemorySaver 里找回「上次暂停的状态」。
// 这就是 Agent 的「断点续跑」能力。
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Note 2: 用 @Controller (不是 @RestController), 因为 index() 要返回视图名。
// 需要返回 JSON 的方法单独加 @ResponseBody。
@Controller
public class AgentController {

    // Note 3: 注入 ReactAgent (在 AgentConfiguration 里建好的 Bean)。
    private final ReactAgent reactAgent;

    // Note 4: ★ 内存缓存, 存「被暂停的会话状态」。
    // key   = threadId (会话标识, 由前端传)
    // value = InterruptionMetadata (Agent 暂停时的快照, 含待审批的工具调用)
    // 用 ConcurrentHashMap 而非 HashMap: Web 环境多线程并发, 需要线程安全。
    // 注意: 这个 map 在内存里, 重启就丢; 生产环境应该用 Redis/DB 持久化。
    private final Map<String, InterruptionMetadata> map = new ConcurrentHashMap<>();

    public AgentController(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    // Note 5: ★★★ 第一步: 触发 Agent 执行, 拿到「待审批清单」。
    // 用户带着 query (问题) 和 threadId (会话 ID) 来调这个接口。
    @GetMapping("/invoke")
    @ResponseBody
    public List<InterruptionMetadata.ToolFeedback> invoke(@RequestParam("query") String query,
                       @RequestParam("threadId") String threadId
    ) throws Exception {
        // Note 6: RunnableConfig 是 Agent 执行的「配置包」, 这里塞了 threadId。
        // threadId 的作用: 让 Agent 知道这次调用属于哪个会话,
        // 这样它可以从 MemorySaver 里存/取状态, 实现多轮 + 断点续跑。
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId(threadId).build();
        // Note 7: ★ 核心调用: reactAgent.invokeAndGetOutput(query, runnableConfig)
        //   query          用户的提问 (如 "把 hello 写到 a.txt")
        //   runnableConfig 含 threadId 的配置
        // 返回值是 Optional, .orElseThrow() 在空时抛异常。
        //
        // Agent 内部流程:
        //   LLM 思考 → 决定调 file_write → 命中 approvalOn 钩子 → 暂停
        //   暂停时返回 InterruptionMetadata (含「要写 a.txt, 内容 hello」这工具调用信息)
        InterruptionMetadata metadata = (InterruptionMetadata) reactAgent.invokeAndGetOutput(query, runnableConfig).orElseThrow();
        // Note 8: 把暂停状态存进 map, key 是 threadId。
        // 第二步 /feedback 时凭 threadId 取回这个 metadata, 继续处理。
        map.put(threadId, metadata);
        // Note 9: 返回「待审批的工具调用清单」给前端。
        // toolFeedbacks() 是一个 List, 每项含「工具名+参数+是否待审批」。
        // 前端拿到后展示给用户:「Agent 想执行 file_write 写 a.txt, 同意吗?」
        return metadata.toolFeedbacks();
    }

    // Note 10: ★★★ 第二步: 用户提交审批反馈, Agent 继续执行。
    // 前端把用户的「批准/拒绝」决定 POST 过来, 带上同一个 threadId。
    @PostMapping("/feedback")
    @ResponseBody
    public String feedback(@RequestBody List<Feedback> feedbacks,
                         @RequestParam("threadId") String threadId
    ) throws Exception {
        // Note 11: 凭 threadId 取回第一步暂停时的状态。
        InterruptionMetadata metadata = map.get(threadId);
        // Note 12: 找不到说明会话过期或没调过 /invoke, 直接返回提示。
        // 这是防御性编程: 防止用户直接调 /feedback 而没先调 /invoke。
        if(metadata == null) {
            return "no metadata found";
        }
        // Note 13: 校验: 用户提交的反馈数量必须和待审批工具数量一致。
        // 防止前端漏传或多传, 否则后面 for 循环会越界或漏处理。
        if(metadata.toolFeedbacks().size() != feedbacks.size()) {
            return "feedback size not match";
        }

        // Note 14: ★ 用原 metadata 重建一个新的 InterruptionMetadata.Builder。
        // 为什么要重建而不是改原对象: InterruptionMetadata 是不可变的 (设计上), 改要新建。
        // .nodeId(metadata.node())  保留原暂停节点 ID (Agent 要从这个节点恢复)
        // .state(metadata.state())  保留原状态 (含之前的对话历史等)
        InterruptionMetadata.Builder newBuilder = InterruptionMetadata.builder()
                .nodeId(metadata.node())
                .state(metadata.state());
        // Note 15: 遍历每个待审批的工具调用, 根据用户反馈设置批准/拒绝。
        for (int i = 0; i < feedbacks.size(); i++) {
            // Note 16: 取第 i 个待审批工具调用 (原状态)。
            var toolFeedback = metadata.toolFeedbacks().get(i);
            // Note 17: 基于原 toolFeedback 创建一个可编辑的 Builder。
            // 这样能保留原工具调用的所有信息 (工具名、参数等), 只改审批结果。
            InterruptionMetadata.ToolFeedback.Builder editedFeedbackBuilder = InterruptionMetadata.ToolFeedback
                    .builder(toolFeedback);
            // Note 18: 根据用户的批准/拒绝决定, 设置不同的审批结果。
            if(feedbacks.get(i).isApproved()) {
                // Note 19: 批准 → 设为 APPROVED。Agent 恢复后会真正执行这个工具调用。
                editedFeedbackBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
            } else {
                // Note 20: 拒绝 → 设为 REJECTED + 附上拒绝原因 (用户填的 feedback)。
                // Agent 恢复后不会执行该工具, 而是把「用户拒绝+原因」告诉 LLM, LLM 会改用其他方案或告知用户。
                editedFeedbackBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                        .description(feedbacks.get(i).feedback());
            }
            // Note 21: 把编辑好的反馈加进新 Builder。
            newBuilder.addToolFeedback(editedFeedbackBuilder.build());
        }
        // Note 22: ★ 构造「恢复执行」用的 RunnableConfig。
        // 关键: .addMetadata(HUMAN_FEEDBACK_METADATA_KEY, newBuilder.build())
        // 把用户的审批结果作为元数据塞进配置, Agent 恢复时会读这个元数据,
        // 知道「哪些工具被批准了, 哪些被拒绝了」, 据此决定是否真正执行。
        RunnableConfig resumeRunnableConfig = RunnableConfig.builder().threadId(threadId)
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, newBuilder.build())
                .build();
        // Note 23: ★ 第二次调用 Agent, 但这次 query 是空串 ""。
        // 为什么空: 因为这不是新问题, 是「恢复上次暂停的执行」。
        // Agent 凭 threadId 从 MemorySaver 取回暂停状态, 凭 HUMAN_FEEDBACK_METADATA_KEY 取回审批结果,
        // 然后从暂停点继续: 执行被批准的工具, 跳过被拒绝的, 最后 LLM 生成最终回答。
        reactAgent.invokeAndGetOutput("", resumeRunnableConfig);
        // Note 24: 返回成功。实际生产环境这里可以返回 Agent 的最终回答。
        return "success";
    }

    // Note 25: 根路径返回 "index" 视图 (对应 templates/index.html 之类的页面)。
    // 这是给前端 demo 页面用的入口, 不涉及 Agent 逻辑。
    @GetMapping
    public String index() {
        return "index";
    }
}
