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

// Note 1: 本类演示两种「轻量级」结构化输出——Map 和 List,用于结构不固定或不需要
// 预定义 Bean 的场景。与 BeanController 的对比:
//
//   BeanController: 输出→强类型 BeanEntity (title, author, date, content 字段固定)。
//   本类:           输出→Map<String, Object> (字段动态) 或 List<String> (纯列表)。
//
// 适用场景:
//   Map:  让 LLM 自由决定输出哪些字段 (如「分析这段代码的问题」→ {issues:[...], severity:"high"})。
//   List: 让 LLM 输出列表 (如「列出 5 个优化建议」→ ["建议1", "建议2", ...])。
//
// 关键类型:
//   MapOutputConverter:  把 LLM 输出转成 Map<String, Object>。内部用 SnakeYAML/YAML 解析,
//                        所以要求 LLM 输出 YAML 或类 JSON 格式。
//   ListOutputConverter: 把 LLM 输出转成 List<String>。按逗号/换行分隔。
//   ChatClientAttributes.OUTPUT_FORMAT: 通过 Advisor 参数注入 format 指令。
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/map-list")
public class MapListController {

    // Note 2: 日志用了 BeanController.class——原代码就是这样,实际项目应改为 MapListController.class。
    private static final Logger log = LoggerFactory.getLogger(BeanController.class);

    private final ChatClient chatClient;
    // Note 3: 两个 Converter 在构造时创建,全局复用。
    // MapOutputConverter 不需要额外配置,直接用。
    // ListOutputConverter 需要传入 ConversionService (默认实现即可),用于字符串→列表的转换。
    private final MapOutputConverter mapConverter;
    private final ListOutputConverter listConverter;

    public MapListController(ChatClient.Builder builder) {
        // map转换器
        this.mapConverter = new MapOutputConverter();
        // list转换器
        // Note 4: DefaultConversionService 是 Spring 的类型转换服务,提供 String→List 等
        // 标准转换。ListOutputConverter 用它来做最终的格式转换。
        this.listConverter = new ListOutputConverter(new DefaultConversionService());

        this.chatClient = builder
                .build();
    }

    // Note 5: Map 输出——返回值直接是 Map<String, Object>,无需手动反序列化。
    // 关键机制: 通过 .advisors() 传入 OUTPUT_FORMAT 参数,让框架自动在 prompt 中
    // 注入 format 指令,然后自动解析 LLM 输出为 Map。
    //
    // 这里的 advisor 不是之前学的「拦截器 Advisor (BaseAdvisor)」,而是通过
    // ChatClientAttributes 传参——本质上是告诉框架「请用 MapOutputConverter 的格式」,
    // 框架内部会自动处理 format 注入 + 结果解析。
    @GetMapping("/chatMap")
    public Map<String, Object> chatMap(@RequestParam(value = "query", defaultValue = "请为我描述下影子的特性") String query) {
        return chatClient.prompt(query)
                .advisors(
                        // Note 6: ChatClientAttributes.OUTPUT_FORMAT 是一个约定 key,
                        // 值设为 converter.getFormat() 返回的格式指令。框架内置的 Advisor
                        // 会读取这个参数,自动把格式指令注入 prompt,并在响应后自动解析。
                        a -> a.param(ChatClientAttributes.OUTPUT_FORMAT.getKey(), mapConverter.getFormat())
                ).call().entity(mapConverter);
    }

    // Note 7: List 输出——返回值直接是 List<String>。机制与 chatMap 完全一致,
    // 只是换了 ListOutputConverter。适用场景: 让 LLM 输出纯列表,如「列出5个...」。
    @GetMapping("/chatList")
    public List<String> chatList(@RequestParam(value = "query", defaultValue = "请为我描述下影子的特性") String query) {
        return chatClient.prompt(query)
                .advisors(
                        a -> a.param(ChatClientAttributes.OUTPUT_FORMAT.getKey(), listConverter.getFormat())
                ).call().entity(listConverter);
    }
}
