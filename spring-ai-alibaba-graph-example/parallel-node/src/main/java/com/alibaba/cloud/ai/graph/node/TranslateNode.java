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

// Note 1: TranslateNode 是并行图里的「翻译工作节点」——负责把用户 query 翻译成目标语言。
//
// 它和 ExpanderNode 是「并行双分支」的两条腿:
//   dispatcher 分发后, TranslateNode 和 ExpanderNode 同时开跑, 各干各的, 互不等待。
//   两个都跑完, collector 才收集结果。
//
// 关键接口 NodeAction:
//   apply(OverAllState state) → Map<String, Object>
//   - 入参 state:  图的当前状态 (能读到别的节点写的数据)
//   - 返回 Map:    本节点要更新的状态字段 (会合并进 state, 给后续节点用)
// 这就是节点间通信方式: 通过 OverAllState 共享数据。
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

// Note 2: implements NodeAction —— 这是 Graph 节点的统一接口。
// 任何想成为图节点 的类, 实现这个接口即可 (类比 Tool 的 @Tool 注解, 这是另一种「成为节点」的方式)。
public class TranslateNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(TranslateNode.class);

    // Note 3: PromptTemplate 是 Spring AI 的提示词模板, {targetLanguage} 和 {query} 是占位符。
    // 这个 prompt 让 LLM 把 query 翻译成 targetLanguage, 且不附加任何解释 (只要译文)。
    // 模板抽成常量复用, 避免每次 apply 都重建。
    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("Given a user query, translate it to {targetLanguage}.\nIf the query is already in {targetLanguage}, return it unchanged.\nIf you don't know the language of the query, return it unchanged.\nDo not add explanations nor any other text.\n\nOriginal query: {query}\n\nTranslated query:\n");

    private final ChatClient chatClient;

    // Note 4: 默认目标语言英文。可被 state 里的 translate_language 覆盖。
    private final String TARGET_LANGUAGE = "English";

    public TranslateNode(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // Note 5: ★ 节点核心方法。每次图执行到 translator 节点时调这个方法。
    @Override
    public Map<String, Object> apply(OverAllState state) {
        logger.info("translate node is running.");

        // Note 6: ★ 并行协调机制——通过 status 字段控制「该不该真的执行」。
        // dispatcher 节点会把 translate_status 设为 "assigned", 表示「分配给你了, 该干活」。
        // 这里检查: 如果不是 assigned, 就什么都不做 (返回空 Map)。
        //
        // 为什么需要这个: 因为并行图里 collector 可能因为「结果没齐」而回到 dispatcher 重跑,
        // 重跑时 dispatcher 不会再设 assigned (状态已是 processing),
        // translator 这时就会跳过, 避免重复翻译。这是一种「幂等保护」。
        String translateStatus = state.value("translate_status", "");
        logger.info("Current translate_status: {}", translateStatus);

        if (!"assigned".equals(translateStatus)) {
            logger.info("Translate status is not assigned, skipping LLM call");
            return Map.of();  // 空 Map = 不更新任何状态
        }

        // Note 7: 从 state 读输入数据。query 是用户原始问题, translate_language 是目标语言。
        // state.value(key, defaultValue) 第二个参数是兜底值, 字段不存在时用。
        String query = state.value("query", "");
        String targetLanguage = state.value("translate_language", TARGET_LANGUAGE);

        logger.info("Calling LLM for translation, setting status to processing");

        // Note 8: ★ 调 LLM 做翻译。用流式 (stream) 而非同步 (call),
        // 因为整个图是流式的 (Controller 用 SSE 推给前端), 节点也要产出 Flux。
        // .user() 设用户消息, 用 PromptTemplate 的模板 + 参数填充。
        // .stream().chatResponse() 返回 Flux<ChatResponse> (流式响应)。
        Flux<ChatResponse> chatResponseFlux = this.chatClient.prompt().user((user) -> user.text(DEFAULT_PROMPT_TEMPLATE.getTemplate()).param("targetLanguage", targetLanguage).param("query", query)).stream().chatResponse();

        // Note 9: ★ 返回要更新的状态:
        //   translate_content = 翻译结果 (Flux, 后续 collector 会检查它是否存在)
        //   translate_status = "processing" (表示「正在处理」, 防止重跑时重复翻译)
        //
        // 关键: 这里把 Flux 直接塞进 state! 不是等翻译完, 而是把「流」本身作为结果。
        // collector 检查的是「这个字段存在吗」, 不是「流跑完了吗」。
        return Map.of(
            "translate_content", chatResponseFlux,
            "translate_status", "processing"
        );
    }
}
