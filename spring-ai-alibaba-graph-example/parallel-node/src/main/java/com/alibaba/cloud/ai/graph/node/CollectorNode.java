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

// Note 1: ★ CollectorNode 是并行图的「fan-in 收集节点」——等两条并行腿都跑完, 再决定下一步。
//
// 在图结构里它的位置:
//   translator ──┐
//                ├──→ collector ──→ (END 或 回 dispatcher)
//   expander  ──┘
//
// collector 的核心职责:
//   1. 检查两路结果 (translate_content + expander_content) 是否都到齐
//   2. 都到齐 → 设 collector_next_node = END (结束)
//   3. 没到齐 → 设 collector_next_node = dispatcher (回到 dispatcher 重跑, 形成循环等待)
//
// ★ 这就是 fan-in 的精髓: 多个并行分支汇聚到一个节点, 它负责「等齐 + 决策」。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

public class CollectorNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(CollectorNode.class);

    // Note 2: 等待 5 秒。这是「轮询等待」机制——并行分支可能还没跑完,
    // collector 先睡 5 秒再检查, 给并行分支时间产出结果。
    // (生产环境一般用更优雅的异步等待, 这里用 sleep 是简化教学。)
    private static final long TIME_SLEEP = 5000;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {

        // Note 3: ★ 睡 5 秒——等待 translator 和 expander 把结果写进 state。
        // 因为它们是并行的, 调度到 collector 时可能还没完成 LLM 调用。
        Thread.sleep(TIME_SLEEP);

        logger.info("collector node is running.");
        // Note 4: 默认下一步是 END (结束)。如果结果没齐, 会被改成 "dispatcher"。
        String nextStep = END;
        Map<String, Object> updated = new HashMap<>();

        // Note 5: ★ 关键检查——两路结果都到齐了吗?
        // areAllExecutionResultsPresent 检查 translate_content 和 expander_content 都存在。
        // 如果没到齐, 把 nextStep 改成 "dispatcher", 让图回到 dispatcher 重跑 (循环等待)。
        if (!areAllExecutionResultsPresent(state)) {
            nextStep = "dispatcher";
        }

        // Note 6: 把决策结果写进 collector_next_node。
        // 这个字段会被 CollectorDispatcher (边动作) 读到, 决定图的实际跳转。
        updated.put("collector_next_node", nextStep);
        logger.info("collector node -> {} node", nextStep);
        return updated;
    }

    // Note 7: ★ 检查两路结果是否都到齐。
    // state.value("xxx").isPresent() 判断字段是否存在 (不是判空, 是判「有没有这个 key」)。
    // translator 写 translate_content, expander 写 expander_content。
    // 两个都 present = 两路都产出结果了 = 可以结束了。
    // 任一缺失 = 有一路还没完成 = 需要回 dispatcher 重跑 (其实重跑时工作节点会因 status≠assigned 而跳过,
    //   只是再走一遍流程让 collector 再检查一次)。
    public boolean areAllExecutionResultsPresent(OverAllState state) {
        return state.value("translate_content").isPresent() && state.value("expander_content").isPresent();
    }

}
