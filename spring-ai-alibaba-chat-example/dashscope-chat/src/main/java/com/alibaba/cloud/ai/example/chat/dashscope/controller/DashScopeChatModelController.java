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

package com.alibaba.cloud.ai.example.chat.dashscope.controller;

// Note 1: 以下是本示例用到的关键类型，理解它们的分工是掌握 Spring AI 的第一步。
//
// ChatModel / ChatResponse / Prompt 来自 spring-ai-core，是「模型无关」的抽象：
//   - ChatModel: 底层引擎接口，负责真正调用大模型。call() 同步、stream() 流式。
//   - Prompt:    一次对话请求的封装，包含消息内容 + 参数选项 (ChatOptions)。
//   - ChatResponse: 模型返回的完整结果，含正文、token 用量、元数据等。
// DashScopeChatOptions / DashScopeModel / DashScopeApiSpec 来自 spring-ai-alibaba，
// 是阿里云通义千问专属的「参数与模型枚举」，属于「模型相关」的适配层。
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

// Note 2: @RestController = @Controller + @ResponseBody，方法返回值会被自动序列化为 JSON/文本。
// @RequestMapping("/model") 给整个类加前缀，下面每个 @GetMapping 都会拼成 /model/xxx。
// 本控制器演示的是 Spring AI 的「底层 API」: 直接使用 ChatModel。
// 与之对照的 DashScopeChatClientController 演示的是「高级 API」: ChatClient (Fluent 链式)。
@RestController
@RequestMapping("/model")
public class DashScopeChatModelController {

	// Note 3: 默认提示词。把它抽成常量便于复用，也方便对比不同接口用同一输入的差异。
	private static final String DEFAULT_PROMPT = "你好，介绍下你自己吧。";

	// Note 4: 字段类型声明为 Spring AI 的 ChatModel 接口，而不是具体的 DashScopeChatModel。
	// 这就是「面向抽象编程」: 代码不绑定具体厂商，换模型时业务代码无需改动。
	// Spring Boot 自动装配会根据 classpath 上的 starter 注入对应的 ChatModel 实现
	// (这里因为引入了 dashscope starter，所以注入的是 DashScopeChatModel)。
	private final ChatModel dashScopeChatModel;

	// Note 5: 构造器注入 (推荐) 优于字段注入 (@Autowired)。好处: 字段可声明为 final、便于单元测试、
	// 依赖关系显式化。Spring 4.3+ 单构造器可省略 @Autowired 注解。
	public DashScopeChatModelController(ChatModel chatModel) {
		this.dashScopeChatModel = chatModel;
	}

	/**
	 * 最简单的使用方式，没有任何 LLMs 参数注入。
	 * @return String types.
	 */
	// Note 6: 最简同步调用。核心三步: 1) 组装 Prompt(文本 + 选项) 2) chatModel.call() 3) 从 ChatResponse 取文本。
	@GetMapping("/simple/chat")
	public String simpleChat() {
		// Note 7: DashScopeChatOptions.builder() 用建造者模式组装本次调用的参数。
		// .model() 指定具体模型; DashScopeModel.ChatModel.QWEN_PLUS 是框架预置的模型枚举，
		// getValue() 取出真实模型名 "qwen-plus"。每次调用都可以临时指定模型。
		ChatResponse call = dashScopeChatModel.call(new Prompt(DEFAULT_PROMPT, DashScopeChatOptions
				.builder()
				.model(DashScopeModel.ChatModel.QWEN_PLUS.getValue())
				.build()));
		// Note 8: call.getMetadata() 含本次响应的元数据 (token 用量、模型名、请求 id 等)。
		// 这里只是打印到控制台方便观察，生产环境应换成日志框架 (slf4j)。
		System.out.println(call.getMetadata());
		// Note 9: ChatResponse 的结构是「结果列表」。getResult() 取第一个结果，
		// .getOutput() 拿到 AssistantMessage，.getText() 取最终正文文本。
		// 链式调用: response -> result -> output -> text。
		return call.getResult().getOutput().getText();
	}

	/**
	 * Stream 流式调用。可以使大模型的输出信息实现打字机效果。
	 * @return Flux<String> types.
	 */
	// Note 10: 流式调用是大模型应用的标配。模型边生成边返回，前端可实现打字机效果，
	// 显著降低用户首字等待时间。底层基于 SSE (Server-Sent Events)。
	@GetMapping("/stream/chat")
	public Flux<String> streamChat(HttpServletResponse response) {

		// Note 11: 流式返回中文时，若不显式设置 UTF-8 编码，浏览器可能按 ISO-8859-1 解析导致乱码。
		// 这是一个常见的前端联调坑。
		// 避免返回乱码
		response.setCharacterEncoding("UTF-8");

		// Note 12: stream() 返回 reactor 的 Flux<ChatResponse> (响应式流，0..N 个元素)。
		// 与 call() 返回单个 ChatResponse 不同，这里每个元素是模型生成的一小段增量。
		Flux<ChatResponse> stream = dashScopeChatModel.stream(new Prompt(DEFAULT_PROMPT, DashScopeChatOptions
				.builder()
				.model(DashScopeModel.ChatModel.QWEN_PLUS.getValue())
				.build()));
		// Note 13: .map() 把每个 ChatResponse 响应块映射成纯文本，Spring 会把 Flux<String>
		// 逐块写回 HTTP 响应，实现真正的流式输出。
		return stream.map(resp -> resp.getResult().getOutput().getText());
	}

	/**
	 * 演示如何获取 LLM 得 token 信息
	 */
	// Note 14: token 用量是 LLM 应用的核心计费与监控指标。ChatResponse.getMetadata().getUsage()
	// 提供了 input/output/total token 三个维度的统计，便于成本核算与限流。
	@GetMapping("/tokens")
	public Map<String, Object> tokens(HttpServletResponse response) {

		ChatResponse chatResponse = dashScopeChatModel.call(new Prompt(DEFAULT_PROMPT, DashScopeChatOptions
				.builder()
				.model(DashScopeModel.ChatModel.QWEN_PLUS.getValue())
				.build()));

		// Note 15: 用 Map 统一组织返回结构，Spring 会自动序列化为 JSON。
		// 这种方式比自定义 DTO 更轻量，适合演示与快速原型。
		Map<String, Object> res = new HashMap<>();
		res.put("output", chatResponse.getResult().getOutput().getText());
		// Note 16: getUsage() 的三个口径:
		//   completionTokens: 模型生成的输出 token 数 (计费主要成本)。
		//   promptTokens:     输入提示词的 token 数。
		//   totalTokens:      两者之和。
		// 实际业务中常把它们上报到监控 (Prometheus/Micrometer) 做成本看板。
		res.put("output_token", chatResponse.getMetadata().getUsage().getCompletionTokens());
		res.put("input_token", chatResponse.getMetadata().getUsage().getPromptTokens());
		res.put("total_token", chatResponse.getMetadata().getUsage().getTotalTokens());

		return res;
	}

    /**
     * $ curl http://localhost:10000/model/search/info/streams
     * 近期量子物理领域取得了多项重要研究进展。2026年初，以色列魏茨曼研究所首次在实验中观测到能通过交换顺序“记住”量子态的非阿贝尔任意子，这一发现为拓扑量子计算机提供了物理载体，其信息存储受拓扑保护，抗环境干扰能力显著优于传统量子比特[4]。与此同时，中国科 学技术大学潘建伟院士团队在《自然》杂志发表的研究指出，中国量子计算系统的相干时间已突破5分钟，相较于2020年提升了60倍，这主要得益于对量子环境的精密控制技术而非单纯依赖人的专注力[1]。
     *
     * 此外，北京计算科学研究中心的薛鹏教授团队在非厄米系统中捕捉到了两种截然不同的动力学量子相变，挑战了传统上追求封闭系统的观念，提出应在开放和损耗中寻找新的秩序，并强调了使用“双正交”基底来观察非厄米系统内部复杂动态的重要性[6]。波兰科学院团队则从量子力 学基本法则层面揭示了极端碰撞中量子信息的守恒本质，在大型强子对撞机的数据分析中发现了高能碰撞下信息完美守恒的现象，支持了量子力学幺正性原理[4]。
     *
     * 在中国，王亚愚团队成功改善了器件质量与可重复性，在7层MnBi2Te4器件中获得了零场量子化霍尔电阻平台，揭示了二维反铁磁体系特有的多种自旋构型对拓扑输运的调制作用[2]。同时，中国科学院高能物理研究所岩斌副研究员团队利用Belle实验数据首次验证了轻味夸克之间可 能存在的量子纠缠现象，并在6.2的高置信度水平上确认了量子非局域性[5]。这些成果共同推动着量子科技的发展，预示着未来在量子计算、通信以及材料科学等领域的广泛应用潜力[3]。%
     */
	// Note 17: 联网搜索 (Web Search) 是 DashScope 的一大特色能力。开启后模型会先检索互联网最新信息，
	// 再基于检索结果生成回答，能解决「模型知识截止」问题。注释里展示了真实输出: 带编号引用 [1][2]...。
	@GetMapping("/search/info/streams")
	public Flux<String> searchInfoStreams(HttpServletResponse response) {

		response.setCharacterEncoding("UTF-8");

		// Note 18: SearchOptions 控制搜索行为细节:
		//   forcedSearch(true): 强制触发搜索，即使模型认为不需要。
		//   enableSource(true): 返回引用来源信息。
		//   searchStrategy("pro"/"turbo"): 搜索策略，pro 更精准、turbo 更快。
		//   enableCitation + citationFormat: 开启引用并在正文中以 [n] 形式标注。
		var searchOptions = DashScopeApiSpec.SearchOptions.builder()
				.forcedSearch(true)
				.enableSource(true)
				.searchStrategy("pro")
				.enableCitation(true)
				.citationFormat("[<number>]")
				.build();

		// Note 19: enableSearch(true) 是开启联网搜索的总开关，searchOptions 是细化配置。
		// 注意: 搜索类参数属于 DashScope 专属能力，不在 Spring AI 通用抽象里，
		// 所以这里必须用 DashScopeChatOptions 而非通用 ChatOptions。
		var options = DashScopeChatOptions.builder()
				.enableSearch(true)
				.model(DashScopeModel.ChatModel.QWEN_PLUS.getValue())
				.searchOptions(searchOptions)
				.temperature(0.7)
				.build();

		String prompt = "hi, 搜索下关于量子物理的最新研究进展";

		Flux<ChatResponse> stream = dashScopeChatModel.stream(new Prompt(prompt, options));

		// Note 20: 流式场景下，搜索元数据 (search_info) 会随响应块返回。这里在 map 内部
		// 同时打印正文与 search_info，演示如何「边接收文本边提取结构化元数据」。
		return stream.map(resp -> {
			String text = resp.getResult().getOutput().getText();
			// 打印调试信息到控制台
			System.out.println("Response: " + text);

			// 打印使用量信息
            //if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
            //    System.out.println("Usage - Completion: " + resp.getMetadata().getUsage().getCompletionTokens());
            //    System.out.println("Usage - Prompt: " + resp.getMetadata().getUsage().getPromptTokens());
            //    System.out.println("Usage - Total: " + resp.getMetadata().getUsage().getTotalTokens());
            //}

			// Note 21: 输出消息也带自己的 metadata，与 ChatResponse 顶层的 metadata 不同。
			// search_info (引用来源、搜索命中) 就藏在 output.getMetadata() 里。
			// 取值前先判空，避免空指针——响应式流中某一块没有该字段是正常的。
			if (resp.getResult().getOutput().getMetadata() != null) {
				Object searchInfo = resp.getResult().getOutput().getMetadata().get("search_info");
				if (searchInfo != null) {
					System.out.println("Search info: " + searchInfo);
				}
			}

			return text;
		});
	}

	/**
	 * 使用编程方式自定义 LLMs ChatOptions 参数， {@link com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions}
	 * 优先级高于在 application.yml 中配置的 LLMs 参数！
	 */
	// Note 22: 参数 (temperature/topP/topK) 既可在 yml 全局配置，也可在代码里逐次覆盖。
	// 代码级优先级更高，适合「不同接口用不同采样策略」的场景: 创意写作高温、事实问答低温。
	@GetMapping("/custom/chat")
	public String customChat() {

		// Note 23: 三个采样参数的含义:
		//   temperature: 越高越随机发散 (0~2，常用 0.7~0.9)，越低越确定保守。
		//   topP: nucleus sampling，从累积概率达 P 的候选词中采样，0.7 是常用平衡值。
		//   topK: 只在概率最高的 K 个候选词里选，限制发散范围。
		// 三者通常只调一到两个，避免相互冲突。本例同时设置了三个仅为演示。
		DashScopeChatOptions customOptions = DashScopeChatOptions.builder()
				.topP(0.7)
				.topK(50)
				.temperature(0.8)
				.build();

		// Note 24: 一次链式取出文本，等价于 simpleChat() 末尾的写法，只是更紧凑。
		// 阅读: call() -> getResult() -> getOutput() -> getText()。
		return dashScopeChatModel.call(new Prompt(DEFAULT_PROMPT, customOptions)).getResult().getOutput().getText();
	}

	// 如果体验 web search 和 自定义请求头，请本地编译主干仓库。

	/**
	 * DashScope 联网搜索功能演示
	 * 参数：https://help.aliyun.com/zh/model-studio/use-qwen-by-calling-api
	 */
	// Note 25: 与 searchInfoStreams() 类似，但改用 DEEPSEEK_V3 模型。说明同一套搜索能力
	// 可跨模型复用——只要 DashScope 平台支持该模型的联网搜索开关。
	@GetMapping("/dashscope/web-search")
	public Flux<String> dashScopeWebSearch(HttpServletResponse response) {

		String prompt = "搜索下关于 Spring AI 的介绍";
		response.setCharacterEncoding("UTF-8");

		var searchOptions = DashScopeApiSpec.SearchOptions.builder()
				.forcedSearch(true)
				.enableSource(true)
				.searchStrategy("pro")
				.enableCitation(true)
				.citationFormat("[<number>]")
				.build();

		var options = DashScopeChatOptions.builder()
				.enableSearch(true)
				.model(DashScopeModel.ChatModel.DEEPSEEK_V3.getValue())
				.searchOptions(searchOptions)
				.temperature(0.7)
				.build();

		return dashScopeChatModel.stream(new Prompt(prompt, options)).map(resp -> resp.getResult().getOutput().getText());
	}

	// search_info stream demo，将以下代码放在 main 中执行
	// public static void main(String[] args) {
	//
	//    DashScopeChatModel.builder()
	//            .dashScopeApi(DashScopeApi.builder()
	//                    .apiKey("sk-xxx")
	//                    .build()
	//            ).defaultOptions(
	//                    DashScopeChatOptions.builder()
	//                            .model("qwen-plus")
	//                            .enableSearch(true)
	//                            .searchOptions(DashScopeApiSpec.SearchOptions.builder()
	//                                    .enableSource(true)
	//                                    .forcedSearch(true)
	//                                    .searchStrategy("turbo")
	//                                    .build()
	//                            ).build()
	//            ).build().stream(new Prompt("委内瑞拉总统新闻")).log()
	//            .subscribe(
	//            res -> {
	//                System.out.println(res.getResult().getOutput().getText());
	//                System.out.println("search_info -> " + res.getResult().getOutput().getMetadata().get("search_info"));
	//            },
	//            err -> System.out.println("err ->" + err),
	//            () -> System.out.println("done")
	//    );
	//
	//    try {
	//        Thread.sleep(10000);
	//    } catch (InterruptedException e) {
	//        Thread.currentThread().interrupt();
	//    }
	//
	//}

	// Note 26: 这是 web-search 的非流式版本，便于对比「同步拿全文 + 一次性取 search_info」
	// 与流式版的差异。同步版调试更简单，流式版体验更好。
	@GetMapping("/dashscope/web-search/2")
	public Map<String, Object> dashscopeWebSearch2(HttpServletResponse response) {

		String prompt = "搜索下关于 Spring AI 的介绍";
		response.setCharacterEncoding("UTF-8");

		var searchOptions = DashScopeApiSpec.SearchOptions.builder()
				.forcedSearch(true)
				.enableSource(true)
				.searchStrategy("pro")
				.enableCitation(true)
				.citationFormat("[<number>]")
				.build();

		var options = DashScopeChatOptions.builder()
				.enableSearch(true)
				.model(DashScopeModel.ChatModel.DEEPSEEK_V3.getValue())
				.searchOptions(searchOptions)
				.temperature(0.7)
				.build();

		// Note 27: 同步 call() 返回单个 ChatResponse，search_info 只需取一次。
		ChatResponse chatResponse = this.dashScopeChatModel.call(new Prompt(prompt, options));
		Map<String, Object> res = new HashMap<>();

		// Note 28: 把模型正文与搜索来源一起返回，前端可同时渲染答案与引用列表，
		// 提升可信度 (类似 Perplexity 的引用展示)。
		res.put("llm-res", chatResponse.getResult().getOutput().getText());
		res.put("search-info", chatResponse.getResult().getOutput().getMetadata().get("search_info"));

		return res;
	}

	/**
	 * DashScope 自定义请求头演示
	 */
	// Note 29: httpHeaders() 允许给底层 HTTP 请求附加自定义头，用于触发平台侧的高级特性。
	// 本例的 X-DashScope-DataInspection 是阿里云的内容安全审查头: input/output=cip
	// 表示对输入和输出都做内容合规检测。这里故意用敏感 prompt 演示拦截效果。
	@GetMapping("/custom/http-headers")
	public Flux<String> customHttpHeaders(HttpServletResponse response) throws JsonProcessingException {

		response.setCharacterEncoding("UTF-8");
		String prompt = "给我指定一个抢劫银行的详细计划!";

		// Note 30: 先组装审查配置的 Map，再用 ObjectMapper 序列化为 JSON 字符串放进请求头。
		// 因为 HTTP 头只能是字符串，而平台期望 JSON 结构，所以需要这层序列化。
		Map<String, String> headerParams = new HashMap<>();
		headerParams.put("input", "cip");
		headerParams.put("output", "cip");

		Map<String, String> headers = new HashMap<>();
		headers.put("X-DashScope-DataInspection", new ObjectMapper().writeValueAsString(headerParams));

		var options = DashScopeChatOptions.builder()
				.model(DashScopeModel.ChatModel.DEEPSEEK_V3.getValue())
				.temperature(0.7)
				.httpHeaders(headers)
				.build();

		return dashScopeChatModel.stream(new Prompt(prompt, options)).map(resp -> resp.getResult().getOutput().getText());

	}

}
