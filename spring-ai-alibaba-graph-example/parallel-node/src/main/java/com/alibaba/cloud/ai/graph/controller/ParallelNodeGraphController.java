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

package com.alibaba.cloud.ai.graph.controller;

// Note 1: ★ ParallelNodeGraphController 是并行图的 Web 入口, 用 SSE (Server-Sent Events) 流式返回。
//
// 对比上一站 react 模块的 Controller:
//   react:     compiledGraph.invoke() → 一次性返回最终结果
//   本模块:    compiledGraph.stream() → 流式返回每个节点的实时输出
//
// 为什么流式: 翻译和扩展都是 LLM 生成, 流式能让前端实时看到「翻译中...」「扩展中...」,
// 用户体验比等全部完成再返回好得多。而且并行图两路同时生成, 流式能交织展示。
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;
import com.alibaba.cloud.ai.graph.controller.GraphProcess.GraphProcess;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

@RestController
@RequestMapping("/graph/stream")
public class ParallelNodeGraphController {

    private static final Logger logger = LoggerFactory.getLogger(ParallelNodeGraphController.class);

    // Note 2: 注入的是 StateGraph (ParallelNodeGraphConfiguration 里定义的), 在构造器里 compile() 成 CompiledGraph。
    // StateGraph 是「图定义」, CompiledGraph 是「可执行图」——区别类似「源代码」vs「编译后的程序」。
    private final CompiledGraph compiledGraph;

    public ParallelNodeGraphController(@Qualifier("parallelNodeGraph")StateGraph stateGraph) throws GraphStateException {
        this.compiledGraph = stateGraph.compile();
    }

    // Note 3: ★ 流式接口——produces TEXT_EVENT_STREAM_VALUE 即 SSE。
    // SSE 让服务器能持续推送数据给浏览器, 不用客户端轮询。
    // 适合 LLM 流式生成 + 图节点逐步执行的场景。
    @GetMapping(value = "/expand", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GraphProcess.ChatMessage>> expand(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？", required = false) String query,
                                                @RequestParam(value = "expander_number", defaultValue = "3", required = false) Integer  expanderNumber,
                                                @RequestParam(value = "thread_id", defaultValue = "__default__", required = false) String threadId) throws GraphRunnerException {
        // Note 4: RunnableConfig 带 threadId, 和 react-agent 一样用于会话隔离/状态恢复。
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId(threadId).build();

        // Note 5: 构造图的输入状态。query 和 expander_number 是图的入口数据。
        // 这两个值会成为 OverAllState 的初始字段, 后续节点能读到。
        Map<String, Object> objectMap = new HashMap<>();
        objectMap.put("query", query);
        objectMap.put("expander_number", expanderNumber);

        // Note 6: GraphProcess 是流处理辅助类 (下一个文件), 负责把图的输出转成 SSE 推给前端。
        GraphProcess graphProcess = new GraphProcess(this.compiledGraph);

        // Note 7: ★ Sinks.Many 是 Reactor 的「数据通道」——一端写入, 另一端读出。
        // 这里建一个 sink, graphProcess 往里写 SSE 事件, Controller 方法返回的 Flux 从里读。
        // onBackpressureBuffer: 消费者跟不上时缓冲数据, 不丢弃。
        Sinks.Many<ServerSentEvent<GraphProcess.ChatMessage>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Note 8: ★ compiledGraph.stream() —— 流式执行图!
        // 对比 invoke (一次性), stream 返回 Flux<NodeOutput>, 每个节点产出的数据都流过来。
        // 这样前端能实时看到: dispatcher 运行 → translator 流式翻译 → expander 流式扩展 → collector 收集。
        Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(objectMap, runnableConfig);

        // Note 9: graphProcess 把 NodeOutput 流转成 SSE 事件, 推进 sink。
        // 它在另一个线程里跑 (subscribe 触发), 边产出边推, 不阻塞当前请求线程。
        graphProcess.processStream(nodeOutputFlux, sink);

        // Note 10: 返回 sink.asFlux()——前端订阅这个 Flux 就能持续收到 SSE 事件。
        // doOnCancel: 前端断开连接时记日志 (用户关页面/取消)。
        // doOnError: 出错时记日志。
        return sink.asFlux()
                .doOnCancel(() -> logger.info("Client disconnected from stream"))
                .doOnError(e -> logger.error("Error occurred during streaming", e));
    }


}
