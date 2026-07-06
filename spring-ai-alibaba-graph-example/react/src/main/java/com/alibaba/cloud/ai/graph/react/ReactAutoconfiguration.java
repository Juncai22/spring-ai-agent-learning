/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.graph.react;

// Note 1: ★★★ 这是整个 graph-example/react 的核心配置类。
// 它演示了「用 Graph 实现 ReAct」的关键——和第 1 站 react-agent-example 的本质区别。
//
// 第 1 站 (react-agent-example):
//   ReactAgent.builder().model().tools().hooks().build()
//   → 黑盒, 框架内部跑 ReAct 循环, 你看不见内部
//
// 第 2 站 (本类):
//   1. 构建 ReactAgent (同样用 builder)
//   2. ★ 关键: 关闭 LLM 内部工具执行 (internalToolExecutionEnabled=false)
//      → 让 Graph 框架接管「思考-行动-观察」循环
//   3. ★ reactAgent.getAndCompileGraph() 拿到底层 CompiledGraph
//      → 能打印图结构 (PlantUML), 看清 ReAct 内部
//   4. Controller 用 compiledGraph.invoke() 调用 (而不是 reactAgent.invokeAndGetOutput)
//
// 核心认知: ReactAgent 内部就是一个 Graph! 框架帮你画好了, 这里把它「取出来」让你看见。
import java.util.concurrent.TimeUnit;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ReactAutoconfiguration {

	// Note 2: ★ 第一个 Bean: normalReactAgent —— ReactAgent 本体。
	// 对比第 1 站: 那里用 .model(chatModel).tools(...) 直接配置
	// 这里用 .chatClient(chatClient).resolver(resolver) —— 通过 ChatClient 间接配置。
	// 区别: chatClient 方式更灵活, 能在 ChatClient 上挂 Advisor/默认参数。
	@Bean
	public ReactAgent normalReactAgent(ChatModel chatModel, ToolCallbackResolver resolver) throws GraphStateException {
		// Note 3: ★ 构建 ChatClient, 三个关键配置:
		ChatClient chatClient = ChatClient.builder(chatModel)
			// Note 4: .defaultToolNames("getWeatherFunction")
			// 按名字挂载工具——引用 WeatherAutoConfiguration 注册的 "getWeatherFunction" Bean。
			// 这就是「按名字引用工具」: 不直接 new 工具实例, 用 Bean 名字找。
			// 好处: 工具和 Agent 解耦, 工具实现可独立替换。
			.defaultToolNames("getWeatherFunction")
			// Note 5: .defaultAdvisors(SimpleLoggerAdvisor)
			// 挂日志 Advisor, 打印每次 LLM 调用的请求/响应 (开发调试用)。
			.defaultAdvisors(new SimpleLoggerAdvisor())
			// Note 6: ★★★ 最关键的一行——internalToolExecutionEnabled(false)!
			// 这个参数决定「谁来执行工具循环」:
			//   true (默认): LLM 内部自己执行工具 (调一次 API, LLM 自己调完所有工具再返回)
			//   false:       LLM 只返回「我想调工具」, 但不真调, 把控制权交给 Graph
			//
			// 为什么关掉: 因为我们要用 Graph 接管循环!
			// 关掉后, 流程变成:
			//   LLM 说 "我要调 getWeather" → 返回给 Graph
			//   → Graph 的「工具节点」执行 getWeather
			//   → Graph 把结果喂回 LLM (回到「思考节点」)
			//   → 循环往复
			// 这就是 Graph 实现 ReAct 循环的原理: 用节点+边显式编排, 而不是 LLM 内部黑盒。
			.defaultOptions(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
			.build();

		// Note 7: 构建 ReactAgent。
		// name:        Agent 名字 (日志/多 Agent 场景用)
		// chatClient:  上面建好的 (含工具+Advisor+关闭内部执行)
		// resolver:    ★ 工具解析器, 按 Bean 名字找工具 (从 Spring 容器里找 "getWeatherFunction")
		//              这是配合 .defaultToolNames() 用的——resolver 负责把名字解析成实际 ToolCallback。
		return ReactAgent.builder()
			.name("React Agent Demo")
			.chatClient(chatClient)
			.resolver(resolver)
			.build();
	}

	// Note 8: ★★★ 第二个 Bean: reactAgentGraph —— 把 ReactAgent 内部的 Graph 取出来编译。
	// 这是本站的核心动作: getAndCompileGraph() 拿到底层图结构。
	// 有了 CompiledGraph, 就能用图的方式调用 (compiledGraph.invoke), 还能打印图结构。
	@Bean
	public CompiledGraph reactAgentGraph(@Qualifier("normalReactAgent") ReactAgent reactAgent)
			throws GraphStateException {

		// Note 9: ★ getAndCompileGraph() —— 把 ReactAgent 内部的图「显式化」。
		// ReactAgent 内部本来就是一个 Graph (节点+边), 这个方法把它编译成可执行形式。
		// 返回的 CompiledGraph 是「编译后的图」, 可以直接 invoke, 也可以导出结构。
		CompiledGraph compiledGraph = reactAgent.getAndCompileGraph();

		// Note 10: ★ 把图导出成 PlantUML 格式并打印。
		// PlantUML 是画图的语言, 打印出来你能看到 ReAct 的内部结构:
		//   - 哪些节点 (思考节点、工具节点、结束节点)
		//   - 节点之间的边 (思考→工具→思考 的循环)
		//   - 条件边 (LLM 没要调工具时跳到结束)
		// 这就是「打开黑盒」——看清 ReactAgent 内部是怎么用图实现 ReAct 循环的。
		// 把打印出的 PlantUML 文本贴到 plantuml.com 就能渲染成图。
		GraphRepresentation graphRepresentation = compiledGraph.getGraph(GraphRepresentation.Type.PLANTUML);
		System.out.println("\n\n");
		System.out.println(graphRepresentation.content());
		System.out.println("\n\n");

		return compiledGraph;
	}

	// Note 11: 第三个 Bean: RestClient.Builder —— 配置底层 HTTP 客户端超时。
	// 这是给 ChatModel 调 LLM API 用的 (RestClient 是阻塞式 HTTP 客户端)。
	// 超时设得很长 (10 分钟), 因为 ReAct 循环可能调多次 LLM, 每次都要等。
	@Bean
	public RestClient.Builder createRestClient() {

		// 2. 创建 RequestConfig 并设置超时
		// Note 12: 三个超时:
		//   connectTimeout          TCP 建连超时
		//   responseTimeout         等响应超时
		//   connectionRequestTimeout 从连接池取连接的超时
		// 都设 10 分钟, 因为 ReAct 多轮调用耗时长 (演示用, 生产别这么长)。
		RequestConfig requestConfig = RequestConfig.custom()
			.setConnectTimeout(Timeout.of(10, TimeUnit.MINUTES)) // 设置连接超时
			.setResponseTimeout(Timeout.of(10, TimeUnit.MINUTES))
			.setConnectionRequestTimeout(Timeout.of(10, TimeUnit.MINUTES))
			.build();

		// 3. 创建 CloseableHttpClient 并应用配置
		// Note 13: 用 Apache HttpClient5 作为底层引擎, 套上超时配置。
		HttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();

		// 4. 使用 HttpComponentsClientHttpRequestFactory 包装 HttpClient
		// Note 14: Spring 的 RequestFactory 适配器, 让 RestClient 能用 HttpClient5。
		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

		// 5. 创建 RestClient 并设置请求工厂
		// Note 15: 返回 Builder, Spring 会用它构造 RestClient (给 ChatModel 用)。
		return RestClient.builder().requestFactory(requestFactory);
	}

}
