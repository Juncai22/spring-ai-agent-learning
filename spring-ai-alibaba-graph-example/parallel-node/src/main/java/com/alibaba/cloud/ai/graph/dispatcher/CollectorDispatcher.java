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

package com.alibaba.cloud.ai.graph.dispatcher;

// Note 1: ★ CollectorDispatcher 是「条件边」——它不是节点, 是边上的逻辑, 决定 collector 之后去哪。
//
// 节点 vs 边的区别 (本模块的关键认知):
//   NodeAction (节点):  干活, 返回要更新的状态 (Map)
//   EdgeAction (边):    不干活, 只返回「下一个节点名」(String)
//
// CollectorNode 写了 collector_next_node = END 或 "dispatcher",
// 但图怎么知道要去 END 还是 dispatcher? 就靠这条 EdgeAction 读字段、返回节点名。
//
// 这就是「条件边」: 根据状态动态决定跳转目标。和 ReAct 里 postLlm 的条件边一个道理。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

// Note 2: implements EdgeAction —— 这是「边动作」接口, 和 NodeAction 平级。
// apply(OverAllState) → String, 返回的就是下一个节点的名字。
public class CollectorDispatcher implements EdgeAction {
    @Override
    public String apply(OverAllState state) throws Exception {
        // Note 3: ★ 读 collector_next_node 字段, 决定下一步。
        // CollectorNode 已经把决策写进这个字段了 (END 或 "dispatcher")。
        // 这里读出来返回即可——边本身不做决策, 决策在节点里做好了, 边只负责「执行决策」。
        //
        // 第二个参数 StateGraph.END 是兜底值: 字段不存在时默认去 END (结束)。
        // 这是一种防御: 万一 collector 没设这个字段, 也能安全结束, 不会卡死。
        return (String) state.value("collector_next_node", StateGraph.END);
    }
}
