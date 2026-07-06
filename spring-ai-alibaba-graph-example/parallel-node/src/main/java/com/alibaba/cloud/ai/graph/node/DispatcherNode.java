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

// Note 1: ★ DispatcherNode 是并行图的「fan-out 分发节点」——它不干活, 只负责「通知两条腿该干活了」。
//
// 在图结构里它的位置:
//                      ┌──→ translator (翻译)
//   START → dispatcher ─┤
//                      └──→ expander (扩展)
//
// dispatcher 本身不调 LLM, 它只做一件事: 把 expand_status 和 translate_status 设为 "assigned",
// 这样 translator 和 expander 执行时看到 status=assigned 就知道「该我干活了」。
//
// 为什么需要它: 并行工作节点是「被动」的, 需要一个协调者告诉它们「现在开工」。
// dispatcher 就是这个协调者。这是 fan-out 模式的标准设计。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

public class DispatcherNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(DispatcherNode.class);

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("dispatcher node is running.");

        // Note 2: 收集本节点要更新的状态。用 HashMap 而非 Map.of, 因为 Map.of 不可变且不能加空判断。
        Map<String, Object> updated = new HashMap<>();

        // Note 3: ★ 检查 expand_status——如果为空(首次运行), 设为 "assigned"。
        // "assigned" 是给 ExpanderNode 的信号: 「分配给你了, 该生成变体了」。
        // 如果已经有值 (重跑场景, 比如上次 status=processing), 就不动它 (保持现状)。
        String expandStatus = state.value("expand_status", "");
        if (expandStatus.isEmpty()) {
            updated.put("expand_status", "assigned");
            logger.info("Set expand_status to assigned");
        } else {
            logger.info("expand_status already set to: {}", expandStatus);
        }

        // Note 4: ★ 同样检查 translate_status——为空则设 "assigned", 给 TranslateNode 信号。
        // 两个 status 一起设, 这样 translator 和 expander 在本轮能同时开工 (并行)。
        String translateStatus = state.value("translate_status", "");
        if (translateStatus.isEmpty()) {
            updated.put("translate_status", "assigned");
            logger.info("Set translate_status to assigned");
        } else {
            logger.info("translate_status already set to: {}", translateStatus);
        }

        // Note 5: 返回更新。这两个 status 会被合并进 state,
        // 随后图按边 dispatcher→translator 和 dispatcher→expander 并行执行两个工作节点。
        // 它们各自读到 status=assigned, 就开始调 LLM。
        return updated;
    }
}
