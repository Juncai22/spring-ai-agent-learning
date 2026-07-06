/*
 * Copyright 2024-2025 the original author or authors.
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
package com.alibaba.cloud.ai.graph.react;

// Note 1: ★ ReactController 是本模块的 Web 入口。
// 对比第 1 站 react-agent-example 的 AgentController:
//   第 1 站: 注入 ReactAgent, 调 reactAgent.invokeAndGetOutput()
//   第 2 站: 注入 CompiledGraph, 调 compiledGraph.invoke()  ← 直接用图!
//
// 区别的意义:
//   第 1 站在「Agent 抽象层」操作 (面向封装)
//   第 2 站在「Graph 抽象层」操作 (面向底层)
// 用 Graph 能拿到 OverAllState (图执行的全部状态), 控制更细。
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/react")
public class ReactController {

	// Note 2: 注入 CompiledGraph (ReactAutoconfiguration 里 reactAgentGraph Bean)。
	// 注意用 @Qualifier 指定 Bean 名, 因为可能有多个 CompiledGraph Bean。
	private final CompiledGraph compiledGraph;

	ReactController(@Qualifier("reactAgentGraph") CompiledGraph compiledGraph) {
		this.compiledGraph = compiledGraph;
	}

	// Note 3: ★ 核心接口: GET /react/chat?query=杭州天气怎么样
	// 对比第 1 站: 那里是 /invoke + /feedback 两步 (因为要审批), 这里一步到位 (没审批)。
	@GetMapping("/chat")
	public String simpleChat(String query) {

		// Note 4: ★ compiledGraph.invoke(...) —— 启动图的执行!
		// 参数 Map.of("messages", new UserMessage(query)) 是图的「输入状态」:
		//   messages: 对话消息列表, 这里塞入用户问题
		// 图从这个输入开始, 沿着节点和边跑:
		//   思考节点 (LLM 决策) → 工具节点 (执行 getWeather) → 思考节点 (看结果) → ... → 结束
		//
		// 返回 Optional<OverAllState>: 图跑完后的最终状态。
		// OverAllState 含所有节点的累计数据 (这里是完整的 messages 列表)。
		Optional<OverAllState> result = compiledGraph.invoke(Map.of("messages", new UserMessage(query)));

		// Note 5: 从最终状态里取出 messages (完整对话历史)。
		// result.get() 解包 Optional (假设一定有结果)
		// .value("messages") 取名为 "messages" 的状态值, 返回 Optional
		// .get() 再解包, 拿到 List<Message>
		List<Message> messages = (List<Message>) result.get().value("messages").get();

		// Note 6: 取最后一条消息——那就是 LLM 的最终回答。
		// messages 列表里包含: [用户问题, 工具调用, 工具结果, ..., 最终回答]
		// 最后一条是 AssistantMessage (LLM 的最终输出)。
		AssistantMessage assistantMessage = (AssistantMessage) messages.get(messages.size() - 1);

		// Note 7: 返回最终回答的文本给前端。
		// 注意: 整个 ReAct 循环 (多轮思考+工具调用) 都在 invoke() 里完成了,
		// 调用方只看到「输入问题 → 输出答案」, 中间的循环是图内部跑的。
		return assistantMessage.getText();
	}

}
