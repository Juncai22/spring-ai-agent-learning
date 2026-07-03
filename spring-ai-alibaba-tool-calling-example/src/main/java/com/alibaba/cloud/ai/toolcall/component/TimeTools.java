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

// Note 1: 这是 Tool Calling 的「最小可用单元」—— 一个 @Tool 注解就把普通方法暴露给 LLM。
//
// 核心思想: LLM 看到工具的 description 后,会决定要不要调用、传什么参数。
// 所以这个类的「代码逻辑」不重要,「description 写得好不好」才重要——
// 描述写得模糊,LLM 就不知道什么时候该用、怎么传参。
//
// 关键注解:
//   @Tool:         把方法标记为可被 LLM 调用的工具。
//   @ToolParam:    描述方法参数,让 LLM 知道该传什么值。
//   description:   ★★★ 最重要的字段。LLM 靠这个文本理解工具用途,文字越精确、示例越清楚,LLM 调得越准。
import com.alibaba.cloud.ai.toolcalling.time.GetTimeByZoneIdService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class TimeTools {

    // Note 2: 内部依赖一个真正干活的 Service (GetTimeByZoneIdService)。
    // 模式: Service 干实事, Tools 暴露给 LLM 调用。
    // 这样做的好处: Service 可独立测试, Tools 只是「薄薄一层注解 + 转发」。
    private final GetTimeByZoneIdService timeService;

    public TimeTools(GetTimeByZoneIdService timeService) {
        this.timeService = timeService;
    }

    // Note 3: @Tool 的 description 必须用「人话」描述这个工具能干啥、什么时候用。
    // 写"Get the time of a specified city"  LLM 知道: 这是查某城市时间的工具,需要传时区。
    // 写得太抽象("时间工具")  LLM 不知道该何时调用,会乱调或漏调。
    @Tool(description = "Get the time of a specified city.")
    public String getCityTime(@ToolParam(description = "Time zone id, such as Asia/Shanghai")
                                    String timeZoneId) {

        // Note 4: 工具方法的实现就是「调底层 Service,返回结果」。
        // Service 内部用 java.time.ZoneId 计算时区偏移,完全在本地完成,不需要调任何 API。
        return timeService.apply(new GetTimeByZoneIdService.Request(timeZoneId)).description();
    }

}
