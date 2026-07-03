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
package com.alibaba.cloud.ai.toolcall.controller;

// Note 1: 本 Controller 演示 Tool Calling 的第三种暴露方式: MethodToolCallback。
// 这是最「完全控制」的方式——所有元数据 (name/description/schema) 都自己构建, 适合复杂场景。
//
// 三种方式对比:
//   方式 1 (.tools() + @Tool 注解):     简单, 但注解写死, 改不了
//   方式 2 (.toolCallbacks + FunctionToolCallback): 灵活, 但只支持 Function
//   方式 3 (.toolCallbacks + MethodToolCallback):   灵活, 反射任意方法, 完全控制元数据 ← 本类用这个
import com.alibaba.cloud.ai.toolcall.component.AddressInformationTools;
import com.alibaba.cloud.ai.toolcalling.baidumap.BaiduMapSearchInfoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

/**
 * @author yHong
 * @version 1.0
 * @since 2025/4/18 10:41
 */
@RestController
@RequestMapping("/address")
public class AddressController {

    private final ChatClient dashScopeChatClient;
    private final AddressInformationTools addressTools;

    public AddressController(ChatClient chatClient, AddressInformationTools addressTools) {
        this.dashScopeChatClient = chatClient;
        this.addressTools = addressTools;
    }

    /**
     * No Tool
     */
    @GetMapping("/chat")
    public String chat(@RequestParam(value = "address", defaultValue = "北京") String address) throws JsonProcessingException {

        BaiduMapSearchInfoService.Request query = new BaiduMapSearchInfoService.Request(address);

        return dashScopeChatClient.prompt(new ObjectMapper().writeValueAsString(query))
                .call()
                .content();
    }

    /**
     * Methods as Tools - MethodToolCallback
     */
    // Note 2: ★★★ 这是最完整的「方法转 Tool」示例, 展示了 4 个底层组件的组合用法。
    //
    // 完整流程拆解:
    //   ① ReflectionUtils.findMethod():  反射拿到方法对象, 避免硬编码方法引用。
    //                                   这种「按名字+参数类型找方法」是 Spring 内部常用手法。
    //   ② ToolDefinition.builder():     手动构造工具定义, name/description 都自己写。
    //                                   description 写得很长, 给了多个 LLM 可识别的触发场景,
    //                                   这是高级用法——用「多个场景描述」提高 LLM 识别准确率。
    //   ③ JsonSchemaGenerator.generateForMethodInput(method):
    //                                   ★ 关键能力——根据方法参数类型, 自动生成 JSON Schema。
    //                                   不用手写 "{\"type\":\"object\",\"properties\":{...}}",
    //                                   Spring AI 帮你反编译方法签名生成。
    //   ④ MethodToolCallback.builder():  把方法 + 定义 + 对象实例组合成可执行的 Tool Callback。
    //                                   toolObject 是必须的——告诉框架调用方法时用哪个实例。
    @GetMapping("/chat-method-tool-callback")
    public String chatWithBaiduMap(@RequestParam(value = "address", defaultValue = "北京") String address) throws JsonProcessingException {

        // Note 3: 反射找方法。参数: 类对象、方法名、参数类型 Class。
        // 这种写法比直接写方法引用 (AddressInformationTools::getAddressInformation) 更动态,
        // 适合「方法名/参数运行时才知道」的场景。
        Method method = ReflectionUtils.findMethod(AddressInformationTools.class, "getAddressInformation", String.class);

        if (method == null) {
            throw new RuntimeException("Method not found");
        }

        return dashScopeChatClient.prompt(address)
                .toolCallbacks(MethodToolCallback.builder()
                        .toolDefinition(ToolDefinition.builder()
                                // Note 4: ★ 多场景描述技巧——同一工具写多个 use case,
                                // 让 LLM 在更多场景下都能识别「该用这个工具」。
                                // "Search for places..." / "Get detail..." / "Get address..." 都指向同一方法。
                                .description("Search for places using Baidu Maps API "
                                        + "or Get detail information of a address and facility query with baidu map or "
                                        + "Get address information of a place with baidu map or "
                                        + "Get detailed information about a specific place with baidu map")
                                // Note 5: name 必须符合 LLM 函数调用规范 (字母/数字/下划线),
                                // LLM 在决策时按这个名字找工具。
                                .name("getAddressInformation")
                                // Note 6: 自动生成 JSON Schema 给 LLM 看。
                                // 假设方法签名是 getAddressInformation(String address),
                                // 自动生成的 schema 类似: {"type":"object","properties":{"address":{"type":"string"}}}
                                .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                                .build())
                        .toolMethod(method)
                        // Note 7: toolObject 指定「方法在哪个对象上调用」。
                        // 框架会在 LLM 决策后, 用这个对象实例去 invoke method。
                        .toolObject(addressTools)
                        .build())
                .call()
                .content();
    }
}
