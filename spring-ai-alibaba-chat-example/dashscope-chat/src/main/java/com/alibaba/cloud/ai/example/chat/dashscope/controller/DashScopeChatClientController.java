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

// Note 1: 本控制器演示 Spring AI 的「高级 API」——ChatClient。
// ChatClient 是 ChatModel 之上的 Fluent (链式) 封装，写法更简洁，且支持 Advisor (拦截器) 机制。
// 与 DashScopeChatModelController (底层 ChatModel API) 形成对照: 两者做同样的事，但风格不同。
//
// 关键类型分工:
//   ChatClient:        高级入口，.prompt().call().content() 链式调用。
//   ChatModel:         底层引擎，被 ChatClient 内部持有，构造时注入。
//   UserMessage:       用户消息，可携带文本 + 媒体 (图片/音频)，用于多模态。
//   Media:             媒体抽象，封装图片/音频数据 (URL 或资源)。
//   SimpleLoggerAdvisor: Advisor 的一种实现，把请求/响应打印到日志，便于调试。
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.chat.MessageFormat;
import com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import java.net.URI;
import java.util.List;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@RestController
@RequestMapping("/client")
public class DashScopeChatClientController {

	private static final String DEFAULT_PROMPT = "你好，介绍下你自己！";

	// Note 2: 持有的是 ChatClient (高级 API) 而非 ChatModel。
	// ChatClient 是不可变的、线程安全的，通常在构造时一次性建好，后续复用。
	private final ChatClient dashScopeChatClient;

	// Note 3: 注入的是底层的 ChatModel，再用它「建造」出 ChatClient。
	// 这种「注入底层、包装成高级」的模式让你能在同一个类里灵活组合。
	public DashScopeChatClientController(ChatModel chatModel) {

		// 构造时，可以设置 ChatClient 的参数
		// {@link org.springframework.ai.chat.client.ChatClient};
		// Note 4: ChatClient.builder(chatModel) 开始建造。builder 上设置的 defaultXxx
		// 会成为该 client 所有调用的「默认值」，单次调用仍可用 .options() 覆盖。
		this.dashScopeChatClient = ChatClient.builder(chatModel)
				// 实现 Logger 的 Advisor
				// Note 5: defaultAdvisors() 注册 Advisor (拦截器)。SimpleLoggerAdvisor
				// 会在每次调用前后打印 prompt 与 response，是开发期最实用的调试工具。
				// Advisor 是 Spring AI 的核心扩展点: 记忆、RAG、日志、改写都通过它实现。
				.defaultAdvisors(
						new SimpleLoggerAdvisor()
				)
				// 设置 ChatClient 中 ChatModel 的 Options 参数
				// Note 6: defaultOptions() 设默认采样参数。这里只设了 topP=0.7。
				// 注意 withXxx 与 xxx() 两种 builder 方法都存在，功能等价 (历史 API 演进)。
				.defaultOptions(
						DashScopeChatOptions.builder()
								.withTopP(0.7)
								.build()
				)
				.build();
	}

	// 也可以使用如下的方式注入 ChatClient
	// public DashScopeChatClientController(ChatClient.Builder chatClientBuilder) {
	//
	//  	this.dashScopeChatClient = chatClientBuilder.build();
	// }
	// Note 7: 另一种注入方式: 直接注入 ChatClient.Builder (Spring 自动配置提供)。
	// 这种方式下，yml 里配置的默认参数会被 Builder 继承，更贴近 Spring Boot 习惯。
	// 选择哪种: 想完全控制就注入 ChatModel 手动 build；想沿用自动配置就注入 Builder。

	/**
	 * ChatClient 简单调用
	 */
	// Note 8: ChatClient 的链式调用精髓: .prompt(文本) 构造请求 -> .call() 同步执行 -> .content() 取文本。
	// 对比 ChatModel 的 call(new Prompt(...))，省去了手动组装 Prompt 和拆解 ChatResponse 的样板代码。
	@GetMapping("/simple/chat")
	public String simpleChat() {

		return dashScopeChatClient.prompt(DEFAULT_PROMPT).call().content();
	}

	/**
	 * ChatClient 流式调用
	 */
	// Note 9: 流式只需把 .call() 换成 .stream()，其余 API 完全一致——这是 Fluent API 的优雅之处。
	// .content() 在流式下返回 Flux<String>，逐块输出文本。
	@GetMapping("/stream/chat")
	public Flux<String> streamChat(HttpServletResponse response) {

		response.setCharacterEncoding("UTF-8");
		return dashScopeChatClient.prompt(DEFAULT_PROMPT).stream().content();
	}


	/**
	 * 图片分析接口 - 通过 URL
	 */
	// Note 10: 多模态 (Multimodal) 调用——让模型「看图说话」。需要用视觉模型 (qwen-vl 系列)。
	// 此接口通过图片 URL 传入，适合图片已托管在公网的场景。
	@GetMapping("/image/analyze/url")
	public String analyzeImageByUrl(@RequestParam(defaultValue = "请分析这张图片的内容") String prompt,
									@RequestParam String imageUrl) {
		try {
			// 创建包含图片的用户消息
			// Note 11: Media 封装一个媒体资源。这里用 MIME 类型 + URI 构造，
			// MIME 告诉模型这是 JPEG 图片。Media 也可包装本地文件、字节数组等。
			List<Media> mediaList = List.of(new Media(MimeTypeUtils.IMAGE_JPEG, new URI(imageUrl)));
			// Note 12: UserMessage 用 builder 组装: .text() 是给模型的文字指令，.media() 挂载图片。
			// 一个消息可同时含文本和多张图片 (mediaList)，模型会综合理解。
			UserMessage message = UserMessage.builder()
					.text(prompt)
					.media(mediaList)
					.build();

			// 设置消息格式为图片
			// Note 13: 通过 metadata 显式声明本消息是图片格式，DashScope 据此走多模态处理分支。
			// MESSAGE_FORMAT 是平台约定的 key，MessageFormat.IMAGE 是其取值。
			message.getMetadata().put(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE);

			// 创建提示词，启用多模态模型
			// Note 14: 多模态专属参数:
			//   withModel("qwen-vl-max-latest"): 必须用视觉模型 (vl = vision-language)，普通 qwen-plus 无法识图。
			//   withMultiModel(true): 告知框架走多模态端点 URL (multimodal-generation)，否则会报 url error。
			//   withVlHighResolutionImages(true): 高分辨率模式，细节更准但耗 token 更多。
			Prompt chatPrompt = new Prompt(message,
					DashScopeChatOptions.builder()
							.withModel("qwen-vl-max-latest")  // 使用视觉模型
							.withMultiModel(true)             // 启用多模态
							.withVlHighResolutionImages(true) // 启用高分辨率图片处理
							.withTemperature(0.7)
							.build());
			// 调用模型进行图片分析
			// Note 15: 即便是多模态，调用方式仍是 .prompt(prompt).call().content()，
			// 与纯文本完全一致——多模态差异都被封装在 Prompt 与 Options 里了。
			return dashScopeChatClient.prompt(chatPrompt).call().content();
		} catch (Exception e) {
			return "图片分析失败: " + e.getMessage();
		}
	}

	/**
	 * 图片分析接口 - 通过文件上传
	 */
	// Note 16: 另一种多模态入口: 接收用户上传的文件 (multipart/form-data)。
	// @PostMapping 因为涉及文件上传必须用 POST，@RequestParam("file") 绑定上传字段名。
	@PostMapping("/image/analyze/upload")
	public String analyzeImageByUpload(@RequestParam(defaultValue = "请分析这张图片的内容") String prompt,
									   @RequestParam("file") MultipartFile file) {
		try {
			// 验证文件类型
			// Note 17: 安全校验: 只接受 image/* 开头的 Content-Type，防止上传非图片文件浪费调用。
			if (!file.getContentType().startsWith("image/")) {
				return "请上传图片文件";
			}

			// 创建包含图片的用户消息
			// Note 18: 与 URL 方式的区别: 这里用 file.getResource() 拿到 Spring Resource，
			// 再用实际 Content-Type 解析 MIME。Media 同样能接受 Resource 类型的数据源。
			Media media = new Media(MimeTypeUtils.parseMimeType(file.getContentType()), file.getResource());
			UserMessage message = UserMessage.builder()
					.text(prompt)
					.media(media)
					.build();

			// 设置消息格式为图片
			message.getMetadata().put(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE);

			// 创建提示词，启用多模态模型
			// Note 19: 选项与 URL 方式完全相同——多模态参数只与「模型 + 消息类型」有关，与图片来源无关。
			Prompt chatPrompt = new Prompt(message,
					DashScopeChatOptions.builder()
							.withModel("qwen-vl-max-latest")  // 使用视觉模型
							.withMultiModel(true)             // 启用多模态
							.withVlHighResolutionImages(true) // 启用高分辨率图片处理
							.withTemperature(0.7)
							.build());

			// 调用模型进行图片分析
			return dashScopeChatClient.prompt(chatPrompt).call().content();

		} catch (Exception e) {
			return "图片分析失败: " + e.getMessage();
		}
	}

}
