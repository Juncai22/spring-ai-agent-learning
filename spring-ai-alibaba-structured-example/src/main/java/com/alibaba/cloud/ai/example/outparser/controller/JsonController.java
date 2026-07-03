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

// Note 1: 本类演示「JSON Mode」——让 LLM 原生支持输出合法 JSON,而不是靠 prompt 里写
// "请输出 JSON" 这种不可靠的方式。这是厂商层面的能力: DashScope/OpenAI 等平台在 API 层
// 支持约束模型输出格式 (Response Format),比纯 prompt 引导更可靠。
//
// 对比 BeanController: BeanController 用 BeanOutputConverter 在「客户端」做转换,
// 模型仍然可能输出不合法的 JSON; JsonController 在「服务端」就约束模型按 JSON 输出,
// 从源头保证格式正确。
//
// 关键类型:
//   DashScopeResponseFormat: DashScope 的响应格式约束。Type.JSON_OBJECT 表示强制 JSON 对象。
//   DashScopeChatOptions.withResponseFormat(): 把格式约束传给本次调用。
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/json")
public class JsonController {

    private final ChatClient chatClient;
    // Note 2: responseFormat 在构造时一次性配置,后续每次调用通过 .options() 传入。
    // 这样所有接口共用同一个格式约束实例,不必每次重新构建。
    private final DashScopeResponseFormat responseFormat;

    public JsonController(ChatClient.Builder builder) {
        // AI模型内置支持JSON模式
        // Note 3: DashScopeResponseFormat.Type.JSON_OBJECT 告诉 DashScope API:
        // 「本请求的输出必须是合法的 JSON 对象」。底层会修改 API 请求参数,
        // 模型在 token 采样阶段就被约束,几乎不会输出非法 JSON。
        // 注意: 这是 DashScope 专属能力,其他厂商有自己的实现 (如 OpenAI 的 response_format)。
        DashScopeResponseFormat responseFormat = new DashScopeResponseFormat();
        responseFormat.setType(DashScopeResponseFormat.Type.JSON_OBJECT);

        this.responseFormat = responseFormat;
        this.chatClient = builder
                .build();
    }

    // Note 4: 不加 JSON Mode 的对照组——只靠 prompt 里写 "请以JSON格式介绍你自己"。
    // 模型可能输出带 markdown 包裹的 JSON (```json ... ```),或者文字 + JSON 混排,
    // 不可靠,需要后处理。这是「纯 prompt 引导」的局限性。
    @GetMapping("/chat")
    public String simpleChat(@RequestParam(value = "query", defaultValue = "请以JSON格式介绍你自己") String query) {
        return chatClient.prompt(query).call().content();
    }

    // Note 5: ★ 加上 JSON Mode 的版本——关键差异是 .options() 里传了 responseFormat。
    // 此时 DashScope API 层会强制模型输出合法 JSON,不会出现 markdown 包裹或文字混排。
    //
    // 使用方式:
    //   1) 构造 DashScopeResponseFormat,设 type=JSON_OBJECT。
    //   2) 在每次调用的 .options() 里通过 withResponseFormat() 传入。
    //   3) 返回的 content() 就是合法 JSON 字符串,可直接 parse。
    //
    // 适用场景: 你只需要 JSON 字符串 (不做 Bean 映射),或者目标结构不固定。
    // 如果需要强类型 Bean,BeanController 的 .entity() 更合适——它内部也用了 format 指令
    // 但额外做了自动反序列化。
    @GetMapping("/chat-format")
    public String simpleChatFormat(@RequestParam(value = "query", defaultValue = "请以JSON格式介绍你自己") String query) {
        return chatClient.prompt(query)
                .options(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)
                                .withResponseFormat(responseFormat)
                                .build()
                )
                .call().content();
    }
}
