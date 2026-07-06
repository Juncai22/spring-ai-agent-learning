/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.graph.node;

// Note 1: ExpanderNode 是并行图里的「查询扩展节点」——把一个 query 扩展成多个变体。
//
// 它和 TranslateNode 是并行的两条腿:
//   用户问 "你好, 介绍一下自己"
//   → TranslateNode 同时: 翻译成英文
//   → ExpanderNode 同时: 生成 3 个变体 (不同角度的问法)
//   两路并行, 互不阻塞。
//
// 结构和 TranslateNode 几乎一样, 区别只在 prompt 和产出的状态字段。
// 这说明: 并行节点之间是「同构」的, 各自独立工作, 通过 state 协调。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

public class ExpanderNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ExpanderNode.class);

    // Note 2: 这个 prompt 让 LLM 生成 {number} 个 query 变体, 每个从不同角度/方面展开。
    // 用途: 搜索优化——一个 query 变多个, 提高检索命中率 (类似 RAG 里的 query expansion)。
    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("You are an expert at information retrieval and search optimization.\nYour task is to generate {number} different versions of the given query.\n\nEach variant must cover different perspectives or aspects of the topic,\nwhile maintaining the core intent of the original query. The goal is to\nexpand the search space and improve the chances of finding relevant information.\n\nDo not explain your choices or add any other text.\nProvide the query variants separated by newlines.\n\nOriginal query: {query}\n\nQuery variants:\n");

    private final ChatClient chatClient;

    // Note 3: 默认生成 3 个变体。可被 state 里的 expander_number 覆盖。
    private final Integer NUMBER = 3;

    public ExpanderNode(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        logger.info("expander node is running.");

        // Note 4: 同 TranslateNode 的状态检查机制——只在 assigned 时才真正执行。
        // 这是并行图里所有工作节点的通用模式: 通过 status 控制执行, 防止重跑时重复调用 LLM。
        String expandStatus = state.value("expand_status", "");
        logger.info("Current expand_status: {}", expandStatus);

        if (!"assigned".equals(expandStatus)) {
            return Map.of();
        }

        String query = state.value("query", "");
        Integer expanderNumber = state.value("expander_number", this.NUMBER);

        logger.info("Calling LLM for expansion, setting status to processing");

        // Note 5: 调 LLM 生成变体, 流式响应。
        Flux<ChatResponse> chatResponseFlux = this.chatClient.prompt().user((user) -> user.text(DEFAULT_PROMPT_TEMPLATE.getTemplate()).param("number", expanderNumber).param("query", query)).stream().chatResponse();

        // Note 6: 产出 expander_content (变体结果 Flux) + 把 expand_status 设为 processing。
        // collector 会检查 expander_content 是否存在, 据此判断这路是否完成。
        return Map.of(
            "expander_content", chatResponseFlux,
            "expand_status", "processing"
        );
    }
}
