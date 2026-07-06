package com.alibaba.cloud.ai.example.combined.controller;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * CombinedController —— 四范式合一示例的 Web 入口。
 *
 * 访问: GET http://localhost:8088/create?query=写一篇关于 Spring AI 的文章
 *
 * 触发完整流程:
 *   supervisor 调度 → research(并行查资料) → writer(写稿) → critic(审查)
 *   → reviser(修订, 如需) → critic(复查) → 返回终稿
 */
@RestController
public class CombinedController {

    private final ReactAgent supervisorAgent;

    public CombinedController(@Qualifier("supervisorAgent") ReactAgent supervisorAgent) {
        this.supervisorAgent = supervisorAgent;
    }

    @GetMapping("/create")
    public String create(@RequestParam(value = "query", defaultValue = "写一篇关于 Spring AI 的技术文章") String query) {
        // 触发 supervisor Agent 执行完整流程
        Optional<OverAllState> result = supervisorAgent.invoke(query);
        if (result.isEmpty()) {
            return "执行失败";
        }
        OverAllState state = result.get();

        // 拼接各阶段输出, 展示完整流程
        StringBuilder output = new StringBuilder();
        output.append("============ 四范式合一: 智能内容创作 ============\n");
        output.append("用户需求: ").append(query).append("\n\n");

        // research 阶段 (并行查资料)
        state.value("research_output").ifPresent(r ->
                output.append("【①+② research_agent 资料研究 (ReAct + 并行)】\n")
                        .append(r).append("\n\n"));

        // writer 阶段
        state.value("writer_output").ifPresent(r ->
                output.append("【① writer_agent 初稿 (ReAct)】\n")
                        .append(r).append("\n\n"));

        // critic 阶段 (Reflection 审查)
        state.value("critic_output").ifPresent(r ->
                output.append("【③ critic_agent 审查 (Reflection)】\n")
                        .append(r).append("\n\n"));

        // reviser 阶段 (Reflection 修订, 如触发)
        state.value("reviser_output").ifPresent(r ->
                output.append("【③ reviser_agent 修订 (Reflection 循环)】\n")
                        .append(r).append("\n\n"));

        output.append("===========================================\n");
        return output.toString();
    }
}
