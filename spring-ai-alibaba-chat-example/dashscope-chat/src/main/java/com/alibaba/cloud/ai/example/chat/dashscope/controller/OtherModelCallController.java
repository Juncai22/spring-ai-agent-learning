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

// Note 1: 本类演示「手动构建 ChatModel」，脱离 Spring Boot 自动装配。
// 适用场景: 需要在运行时动态切换 apiKey、动态指定模型、或为不同请求构造独立的模型实例。
// 自动装配 (前面几个 Controller 用的方式) 适合单一配置；本方式适合多租户、多实例等动态场景。
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 * 阿里云 DashScope 模型文档：https://help.aliyun.com/zh/model-studio/qwen-api-via-dashscope
 *
 * 调用 url 的区别：
 *      纯文本模型（如qwen-plus）：POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation
 *      多模态模型（如qwen3.5-plus或qwen3-vl-plus）POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
 *
 * qwen3.5-plus 需要用 incrementalOutput 参数设为 true。而 incrementalOutput 只有在 stream 下设置才能生效
 *      incremental_output boolean （可选）默认为false（Qwen3-Max、Qwen3-VL、Qwen3 开源版、QwQ 、QVQ模型默认值为 true）
 *      在流式输出模式下是否开启增量输出。推荐您优先设置为true。
 */

// Note 2: 类注释里点出了两个关键 URL 区别: 纯文本走 text-generation 端点，多模态走 multimodal-generation 端点。
// 框架根据 multiModel 参数自动选择端点——这就是为什么多模态必须设 multiModel(true)，否则会报 url error。
@RestController
@RequestMapping("/other-model")
public class OtherModelCallController {

    // Note 3: 每次请求都新建一个 ChatModel 实例 (getChatModel())。这是演示用的写法，
    // 生产环境若配置不变应复用单例，避免重复创建开销。这里强调「能手动 build」这一能力。
    @GetMapping("/stream")
    public Flux<String> call() {

        // Note 4: 手动构建的 ChatModel 同样支持 stream()，API 与自动装配的完全一致。
        // ChatModel 接口屏蔽了构建方式的差异——这正是抽象的价值。
        return getChatModel().stream("hi");
    }

    private ChatModel getChatModel() {

        // Note 5: 两层 builder 嵌套:
        //   外层 DashScopeChatModel.builder() —— 构建模型引擎，注入 API 客户端 + 默认选项。
        //   内层 DashScopeChatOptions.builder() —— 配置本次调用的参数 (模型名、增量输出等)。
        return DashScopeChatModel.builder()
                .defaultOptions(DashScopeChatOptions.builder()
                        // 设置此参数为 false 时：报错
                        // [InvalidParameter] <400> InternalError.Algo.InvalidParameter: This model only supports incremental_output set to True. (requestId: xxx)
                        // Tips：SAA 框架在 stream 调用下，此参数默认为 true，call 调用时为 false
                        // Note 6: incrementalOutput (增量输出) 的坑: 某些模型 (如 qwen3.5-plus) 在流式下
                        // 只支持 true。框架在 stream 时默认 true、call 时默认 false，所以一般不用手动设。
                        // 这里注释掉是为了说明「乱设会报错」——读错误信息能学到框架默认行为。
                        // .incrementalOutput(false)
                        // 不设置此参数时：报错
                        // [InvalidParameter] url error, please check url！ For details, see: https://help.aliyun.com/zh/model-studio/error-code#error-url (requestId: xxx)
                        // Note 7: multiModel(true) 是本例关键: qwen3.5-plus 走多模态端点，不设会报 url error。
                        // 即使你不传图片，只要用多模态模型名就必须开启，让框架选对 URL。
                        .multiModel(true)
                        .model("qwen3.5-plus")
                        .build())
                // Note 8: dashScopeApi() 注入底层 HTTP 客户端。这里手动用 apiKey 构建 DashScopeApi，
                // 而非依赖自动装配——所以本 Controller 能完全脱离 yml 配置独立工作。
                // 注意 "sk-xxx" 是占位符，实际使用需替换为真实 key (或从环境变量/密钥管理服务读取)。
                .dashScopeApi(
                        DashScopeApi.builder()
                                .apiKey("sk-xxx")
                                .build()
                )
                .build();
    }

}
