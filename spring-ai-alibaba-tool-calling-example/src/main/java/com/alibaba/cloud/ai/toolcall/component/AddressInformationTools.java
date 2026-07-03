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
package com.alibaba.cloud.ai.toolcall.component;

// Note 1: 本类展示了 Tool Calling 的「**包装外部服务**」标准模式——
//   Service (真正的 HTTP 调用)  +  Tools (薄薄一层, 暴露给 LLM)。
//
// 关键观察: 这个方法上**没有 @Tool 注解**!
// 这是 Spring AI 的两种 Tool 暴露方式:
//   方式 1 (推荐): 方法上加 @Tool,通过 .tools(component) 批量挂载。  ← TimeTools / CampusScheduleTools 用的
//   方式 2 (高级): 不加注解,通过 MethodToolCallback 编程式挂载,完全自己控。 ← AddressController 用的
// 本类是「方式 2」的 Service 层: 纯业务方法,无注解;是否暴露给 LLM 在 Controller 里决定。
//
// 模式的复用性: 这个类被复用成 Tool 是其中一种用法,也可以作为普通 Service 被 Controller 直接调用。
import com.alibaba.cloud.ai.toolcalling.baidumap.BaiduMapSearchInfoService;

public class AddressInformationTools {

    // Note 2: 内部依赖 BaiduMapSearchInfoService (在 starter-memory-jdbc 同级的 starter-toolcalling-baidumap 里)。
    // 这是阿里云另一个 starter:spring-ai-alibaba-starter-toolcalling-baidumap,自动提供百度地图 API 客户端。
    private final BaiduMapSearchInfoService service;

    public AddressInformationTools(BaiduMapSearchInfoService service) {
        this.service = service;
    }

    // Note 3: 业务方法——把地址字符串传给 Service,拿到百度地图查询结果。
    // 注意: 入参 String address 没有 @ToolParam 描述,因为本类不直接做 Tool。
    // 如果要当 Tool 用,Tool 元数据(名字、描述、参数 schema)由调用方在 Controller 里定义。
    public String getAddressInformation(String address) {

        // Note 4: Service.apply() 是 Spring AI 工具的统一调用接口,返回 FunctionCallContext 包装的结果。
        // .message() 取其中的 message 字段——这是约定俗成的取法 (Service 内部封装了所有响应字段)。
        return service.apply(new BaiduMapSearchInfoService.Request(address)).message();
    }

}
