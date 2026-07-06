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

package com.alibaba.cloud.ai.graph.controller.GraphProcess;

// Note 1: GraphProcess 是「流处理辅助类」——把图执行产生的 NodeOutput 流,
// 转换成前端能消费的 SSE (Server-Sent Event) 事件, 推进 sink。
//
// 它解决一个问题: 图执行产出的是 Flux<NodeOutput> (节点输出),
// 但前端要的是简单的 {节点名, 数据} 消息。这个类做格式转换 + 推送。
//
// 位置: 被 ParallelNodeGraphController 调用, 不直接暴露 HTTP 接口。
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

public class GraphProcess {

    private static final Logger logger = LoggerFactory.getLogger(GraphProcess.class);

    private CompiledGraph compiledGraph;

    public GraphProcess(CompiledGraph compiledGraph) {
        this.compiledGraph = compiledGraph;
    }

    // Note 2: ★ 核心方法: 把 NodeOutput 流转成 SSE 事件推到 sink。
    // 参数:
    //   nodeOutputFlux  图执行产生的节点输出流
    //   sink            SSE 推送通道 (Controller 那边建的, 前端订阅它)
    public void processStream(Flux<NodeOutput> nodeOutputFlux, Sinks.Many<ServerSentEvent<ChatMessage>> sink) {
        nodeOutputFlux
                // Note 3: doOnNext —— 每来一个 NodeOutput, 执行这段逻辑 (不消费它, 只是副作用)。
                .doOnNext(output -> {
                    logger.info("output = {}", output);
                    String nodeName = output.node();  // 产出这个输出的节点名 (dispatcher/translator/expander/collector)
                    ChatMessage chatMessage = null;
                    // Note 4: ★ 区分两种输出:
                    //   StreamingOutput: 流式分块 (LLM 生成的一个个 token 块)
                    //   普通 NodeOutput: 节点的完整状态快照
                    if (output instanceof StreamingOutput<?> streamingOutput) {
                        // Note 5: 流式块——取 chunk (一小段文本), 非空就包成 ChatMessage。
                        // 这是 LLM 边生成边吐的字段, 比如 translator 翻译到 "Hello" 就推一条 "Hello"。
                        String chunk = streamingOutput.chunk();
                        if (chunk != null && !chunk.isEmpty()) {
                            chatMessage = new ChatMessage(nodeName, chunk);
                        }
                    } else {
                        // Note 6: 非流式——取整个 state 的 data (节点产出的完整数据)。
                        chatMessage = new ChatMessage(nodeName, output.state().data());
                    }
                    // Note 7: 把 ChatMessage 包成 SSE 事件推进 sink。
                    // sink 另一端 (Controller 返回的 Flux) 会读到, 推给前端。
                    // tryEmitNext: 非阻塞推送, 失败不抛异常 (返回 EmitResult)。
                    sink.tryEmitNext(ServerSentEvent.builder(chatMessage).build());
                })
                // Note 8: doOnComplete —— 流正常结束, 关闭 sink (告诉前端「没有更多数据了」)。
                .doOnComplete(() -> {
                    // 正常完成
                    sink.tryEmitComplete();
                })
                // Note 9: doOnError —— 出错时把错误传给 sink, 前端能感知到流异常终止。
                .doOnError(e -> {
                    logger.error("Error occurred during streaming", e);
                    sink.tryEmitError(e);
                })
                // Note 10: ★ subscribe() 触发整个流! 没有这一行, 上面的 doOnNext/doOnComplete 都不会执行。
                // Reactor 的流是「冷流」, 必须订阅才会真正开始处理。
                // subscribe 在后台线程跑, 不阻塞 Controller 线程。
                .subscribe();
    }

    // Note 11: ChatMessage record —— 推给前端的消息格式。
    // 两个字段:
    //   node_name  产出这条消息的节点名 (前端可据此分区显示: 翻译区/扩展区)
    //   type       数据 (流式块文本 或 完整 state)
    // @JsonProperty 指定 JSON 字段名 (前端按这个名解析)。
    public record ChatMessage(@JsonProperty("node_name") String nodeName, @JsonProperty("type") Object data) {
    }
}
