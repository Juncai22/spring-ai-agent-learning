package com.alibaba.cloud.ai.example.combined;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 四范式合一示例启动类。
 *
 * 启动后访问: GET http://localhost:8088/create?query=写一篇关于 Spring AI 的文章
 *
 * 该示例同时演示四种 Agent 范式:
 *   ① ReAct       - 每个 Agent 是 ReactAgent, 内部有想-做-看循环
 *   ② 并行        - research_agent 同时调 web_search + knowledge_search
 *   ③ Reflection  - supervisor 编排 writer→critic→reviser 循环直到满意
 *   ④ Supervisor  - supervisor 把 4 个子 Agent 当工具动态调度
 */
@SpringBootApplication
public class CombinedApplication {

    public static void main(String[] args) {
        SpringApplication.run(CombinedApplication.class, args);
    }
}
