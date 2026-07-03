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

package com.alibaba.cloud.ai.example.outparser.controller;

// Note 1: 本类演示「结构化输出」的核心场景——让 LLM 输出 JSON 并自动反序列化为 Java Bean。
// 这是从「聊天机器人」迈向「AI 应用」的关键一步: 之前所有接口返回 String，业务代码要自己
// 解析、自己处理格式异常；用结构化输出后，拿到的是类型安全的实体对象。
//
// 三种方式在本类中都有体现:
//   /chat:           先拿文本，再用 BeanOutputConverter 手动反序列化 (两阶段)。
//   /chat-format:    用 ChatClient.entity() 一行搞定 (推荐)。
//   /chat-model-format: ChatModel 底层 API + PromptTemplate 注入格式指令 (原理展示)。
//   /play:           流式 + 手动清洗 + 反序列化 (生产环境的真实踩坑)。
//
// 关键类型:
//   BeanOutputConverter:  把 LLM 输出的 JSON 文本转成指定 Bean 类型的转换器。
//   ParameterizedTypeReference: 携带泛型信息的类型令牌，让 Converter 知道目标类型。
//   PromptTemplate:      模板引擎，用于把 format 指令注入提示词。
//   StTemplateRenderer:  StringTemplate 渲染器 (Spring AI 默认模板引擎)。
import com.alibaba.cloud.ai.example.outparser.entity.BeanEntity;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/bean")
public class BeanController {

    private static final Logger log = LoggerFactory.getLogger(BeanController.class);

    // Note 2: 同时持有 ChatClient(高级 API) 和 ChatModel(底层 API)，因为本类演示
    // 两种 API 下怎么分别做结构化输出。同时持有 BeanOutputConverter 和它的 format 指令。
    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final BeanOutputConverter<BeanEntity> converter;
    private final String format;

    public BeanController(ChatClient.Builder builder, ChatModel chatModel) {
        this.chatModel = chatModel;

        // Note 3: BeanOutputConverter 是 Spring AI 提供的关键工具。构造时需要告诉它目标类型——
        // 这里用 ParameterizedTypeReference<BeanEntity> 的匿名子类传递泛型信息。
        // 因为 Java 的泛型擦除,直接写 BeanEntity.class 拿不到完整类型; ParameterizedTypeReference
        // 保留了泛型参数,适合 List<BeanEntity> 等复杂类型 (虽然这里只是单个 BeanEntity)。
        this.converter = new BeanOutputConverter<>(
                new ParameterizedTypeReference<BeanEntity>() {
                }
        );
        // Note 4: converter.getFormat() 返回一段格式指令文本,类似:
        // "Your response should be in JSON format. The JSON structure should match the java class com.alibaba.cloud.ai.example.outparser.entity.BeanEntity: {...}"
        // 这段指令会自动注入 prompt,告诉 LLM 按指定结构输出 JSON。
        this.format = converter.getFormat();
        log.info("format: {}", format);
        this.chatClient = builder
                .build();
    }

    // Note 5: 最基础的两阶段模式:
    //   阶段 1: chatClient.prompt().call().content() 拿到文本。
    //   阶段 2: converter.convert(result) 把文本反序列化为 Bean。
    // 缺点: 如果 LLM 没按格式输出 (多了 markdown 标记、少了字段等),convert 会抛异常。
    // 优点: 能在阶段 1 和阶段 2 之间插入清洗/重试逻辑,灵活性最高。
    @GetMapping("/chat")
    public String simpleChat(@RequestParam(value = "query", defaultValue = "以影子为作者，写一篇200字左右的有关人工智能诗篇") String query) {
        String result = chatClient.prompt(query)
                .call().content();

        log.info("result: {}", result);
        assert result != null;
        try {
            // Note 6: converter.convert() 内部用 Jackson/ObjectMapper 做反序列化。
            // 成功时得到 BeanEntity 对象,失败时抛异常——生产环境应 catch 后重试或降级。
            BeanEntity convert = converter.convert(result);
            log.info("反序列成功，convert: {}", convert);
        } catch (Exception e) {
            log.error("反序列化失败");
        }
        return result;
    }

    // Note 7: ★ 推荐写法——.entity(BeanEntity.class) 把「调模型 + JSON 反序列化」一步完成。
    // ChatClient 内部会: 1) 自动注入 format 指令 2) 调模型 3) 反序列化 4) 返回实体。
    // 对比上面 /chat 的两阶段模式,少了几行样板代码,且返回类型直接是 BeanEntity。
    // 这是日常开发最常用的结构化输出写法。
    @GetMapping("/chat-format")
    public BeanEntity simpleChatFormat(@RequestParam(value = "query", defaultValue = "以影子为作者，写一篇200字左右的有关人工智能诗篇") String query) {
        return chatClient.prompt(query)
                .call().entity(BeanEntity.class);
    }

    // Note 8: ChatModel 底层 API 版的结构化输出——用于理解「ChatClient.entity() 背后做了什么」。
    // 步骤: 1) 手动用 PromptTemplate 把 format 注入 query
    //        2) 用 ChatModel.call() 调模型
    //        3) 手动调 converter.convert() 反序列化
    // 当你看到 /chat-format 的 .entity() 一行搞定,就知道它内部就是这个流程的封装。
    @GetMapping("/chat-model-format")
    public String chatModel(@RequestParam(value = "query", defaultValue = "以影子为作者，写一篇200字左右的有关人工智能诗篇") String query) {
        // Note 9: PromptTemplate 把 {format} 占位符替换为 converter 生成的格式指令。
        // 等价于 query + "请按如下 JSON Schema 输出: {...}"。
        String template = query + "{format}";

        PromptTemplate promptTemplate = PromptTemplate.builder()
            .template(template)
            .variables(Map.of("format", format))
            .renderer(StTemplateRenderer.builder().build())
            .build();

        Prompt prompt = promptTemplate.create();
        String result = chatModel.call(prompt)
                .getResult().getOutput().getText();
        log.info("result: {}", result);
        assert result != null;
        try {
            BeanEntity convert = converter.convert(result);
            log.info("反序列成功，convert: {}", convert);
        } catch (Exception e) {
            log.error("反序列化失败");
        }
        return result;
    }

    /**
     * @return {@link BeanEntity}
     */
    // Note 10: /play 接口是「生产环境的真实写照」——流式输出 + 手动清洗 + 反序列化。
    // 为什么需要这一步: 流式输出时 LLM 可能返回不完美的 JSON (多空格、换行、markdown 包裹),
    // 直接用 converter.convert() 大概率失败。所以在反序列化之前,需要对流式结果做清洗。
    //
    // 这个接口演示了四个关键技巧:
    //   1) 在 prompt 里用 outputExample 给 LLM 明确的 JSON 结构参考。
    //   2) 流式收集: Flux.collectList().block() 把流合并为完整字符串。
    //   3) 正则清洗: 去除多余换行/空格/格式问题。
    //   4) 最后才 converter.convert()。
    @GetMapping("/play")
    public BeanEntity simpleChat(HttpServletResponse response) {
        // Note 11: 流式 prompt 中直接内嵌了 outputExample,给 LLM 当模板。
        // 这是比 format 指令更「强硬」的引导: 直接告诉 LLM 你想要什么结构,甚至给了示例字段名。
        // 实际效果通常比单纯的 "请输出 JSON" 好很多。
        Flux<String> flux = this.chatClient.prompt()
                .user(u -> u.text("""
							requirement: 请用大概 120 字，作者为 牧生 ，为计算机的发展历史写一首现代诗;
							format: 以纯文本输出 json，请不要包含任何多余的文字——包括 markdown 格式;
							outputExample: {
								 "title": {title},
								 "author": {author},
								 "date": {date},
								 "content": {content}
							};
							"""))
                .stream()
                .content();

        // Note 12: 流式结果清洗四步曲:
        //   1) flux.collectList().block(): 收集所有流块,合并为一个 List<String>。
        //      block() 阻塞等待——演示代码可以,生产环境用 subscribe 异步处理。
        //   2) String.join("\n", ...): 把所有块拼成完整字符串。
        //   3) replaceAll("\\n", ""): 去除换行符 (流式输出可能在任意位置断开)。
        //   4) replaceAll("\\s+", " "): 合并多个连续空白为单空格。
        //   5-6) 修复 JSON 格式: "key" : value → "key": value (去除引号与冒号间的空格)。
        // 这些清洗步骤是实际项目中的常见需求——LLM 输出的 JSON 几乎总是需要后处理。
        String result = String.join("\n", Objects.requireNonNull(flux.collectList().block()))
                .replaceAll("\\n", "")
                .replaceAll("\\s+", " ")
                .replaceAll("\"\\s*:", "\":")
                .replaceAll(":\\s*\"", ":\"");

        log.info("LLMs 响应的 json 数据为：{}", result);

        // Note 13: 清洗完成后,用 converter.convert() 反序列化。此时 JSON 已是干净格式,
        // 反序列化成功率远高于直接对原始流做 convert。
        return converter.convert(result);
    }
}
