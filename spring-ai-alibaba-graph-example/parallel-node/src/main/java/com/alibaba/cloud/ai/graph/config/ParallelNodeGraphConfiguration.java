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

package com.alibaba.cloud.ai.graph.config;

// Note 1: ★★★ 这是 parallel-node 模块的核心——显式用 addNode/addEdge 画出并行图。
//
// 对比上一站 (react 模块):
//   react:  ReactAgent.builder().build() → 框架内部自动搭图 (预制件, 你看不见节点边)
//   本模块: new StateGraph().addNode().addEdge() → 自己画图 (自由拼装, 节点边全在代码里)
//
// 这就是上一站你问「为什么没看到节点和边」的答案——本模块就是「自己画图」的版本。
// 你能看清每个 addNode 加了什么节点, 每个 addEdge 连了什么边。
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.dispatcher.CollectorDispatcher;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.node.CollectorNode;
import com.alibaba.cloud.ai.graph.node.DispatcherNode;
import com.alibaba.cloud.ai.graph.node.ExpanderNode;
import com.alibaba.cloud.ai.graph.node.TranslateNode;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

@Configuration
public class ParallelNodeGraphConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ParallelNodeGraphConfiguration.class);

    // Note 2: ★ 核心 Bean: parallelNodeGraph —— 整个并行图的定义。
    @Bean
    public StateGraph parallelNodeGraph(ChatClient.Builder chatClientBuilder) throws GraphStateException {

        // Note 3: ★ KeyStrategyFactory —— 状态字段的「合并策略工厂」。
        // 图的节点间通过 OverAllState 共享数据, 当多个节点同时写同一个字段时, 怎么合并?
        // 这里给每个字段注册策略: ReplaceStrategy = 新值直接覆盖旧值 (最简单的策略)。
        //
        // 列出的 8 个字段就是整个图会读写的状态:
        //   query               用户输入
        //   expander_number     要生成几个变体
        //   expander_content    expander 产出的变体结果
        //   translate_language  目标语言
        //   translate_content   translator 产出的翻译结果
        //   collector_next_node collector 决定的下一步
        //   expand_status       expander 的执行状态 (assigned/processing)
        //   translate_status    translator 的执行状态 (assigned/processing)
        //
        // 还有其他策略如 AppendStrategy (追加, 适合累积消息列表), 本例都用 Replace。
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addPatternStrategy("query", new ReplaceStrategy())
                .addPatternStrategy("expander_number", new ReplaceStrategy())
                .addPatternStrategy("expander_content", new ReplaceStrategy())
                .addPatternStrategy("translate_language", new ReplaceStrategy())
                .addPatternStrategy("translate_content", new ReplaceStrategy())
                .addPatternStrategy("collector_next_node", new ReplaceStrategy())
                .addPatternStrategy("expand_status", new ReplaceStrategy())
                .addPatternStrategy("translate_status", new ReplaceStrategy())
                .build();

        // Note 4: ★★★ 显式画图——addNode 加节点, addEdge 连边。
        StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                // Note 5: ★ addNode("名字", node_async(节点实例))
                // node_async() 把同步的 NodeAction 包装成异步 (并行执行需要)。
                // 四个节点:
                //   dispatcher  分发器 (fan-out 起点, 不调 LLM, 只设状态)
                //   translator  翻译 (调 LLM, 并行腿1)
                //   expander    扩展 (调 LLM, 并行腿2)
                //   collector   收集器 (fan-in 终点, 检查结果决定下一步)
                .addNode("dispatcher", node_async(new DispatcherNode()))
                .addNode("translator", node_async(new TranslateNode(chatClientBuilder)))
                .addNode("expander", node_async(new ExpanderNode(chatClientBuilder)))
                .addNode("collector", node_async(new CollectorNode()))

                // Note 6: ★★★ 并行边——fan-out!
                // dispatcher 同时连到 translator 和 expander, 这就是「并行」的来源。
                // 图执行到 dispatcher 后, 会同时启动 translator 和 expander 两个分支, 不等彼此。
                // 并行边
                .addEdge("dispatcher", "translator")
                .addEdge("dispatcher", "expander")
                // Note 7: ★★★ fan-in——两条腿都连到 collector。
                // translator 和 expander 都指向 collector, collector 会等两条腿都到齐。
                // (具体怎么"等", 看 CollectorNode 里的 sleep + 检查机制)
                .addEdge("translator", "collector")
                .addEdge("expander", "collector")

                // Note 8: 起点——图的入口是 dispatcher。
                // StateGraph.START 是特殊节点, 表示「图开始」, 连到 dispatcher 表示「从这里启动」。
                .addEdge(StateGraph.START, "dispatcher")
                // Note 9: ★★★ 条件边——collector 之后去哪, 由 CollectorDispatcher 决定。
                // addConditionalEdges(起点, edge_async(边动作), 映射表):
                //   起点:    "collector"
                //   边动作:  CollectorDispatcher (返回 "dispatcher" 或 END)
                //   映射表:  Map.of("dispatcher", "dispatcher", END, END)
                //            把边动作返回的字符串映射到实际节点名。
                //
                // 这就是循环的来源: 如果 CollectorDispatcher 返回 "dispatcher",
                // 图就回到 dispatcher 节点重跑 (其实工作节点会跳过, 只是再走流程让 collector 重新检查结果)。
                // 如果返回 END, 图结束。
                .addConditionalEdges("collector", edge_async(new CollectorDispatcher()),
                        Map.of("dispatcher", "dispatcher", END, END));

        // Note 10: 同 react 模块——导出 PlantUML 图结构打印。
        // 启动时控制台会打印这段 PlantUML, 贴到 plantuml.com 能看到并行图的样子:
        //   START → dispatcher ─┬→ translator ─┐
        //                       └→ expander  ──┤→ collector →(条件)→ dispatcher (循环) 或 END
        GraphRepresentation representation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
                "parallel translator and expander flow");
        logger.info("\n=== Parallel Translator and Expander UML Flow ===");
        logger.info(representation.content());
        logger.info("==================================\n");

        return stateGraph;
    }

}
