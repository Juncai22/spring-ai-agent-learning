/*
 * Copyright 2026-2027 the original author or authors.
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

package com.alibaba.cloud.ai.example.llm.auditor.hook;

// Note 1: ReviserAgentHook 是 reviser Agent 的「模型钩子」——在 reviser 调 LLM 后清理输出。
//
// 背景: reviser 的 prompt 要求 LLM 在修订完答案后, 输出一行 "---END-OF-EDIT---" 标记结束。
// 但这个标记是给 LLM 自己看的 (标记修订结束), 不应该出现在最终给用户的结果里。
// 本 Hook 的职责: afterModel 时把 "---END-OF-EDIT---" 标记从输出中删除。
//
// 对比 CriticAgentHook (追加引用) vs ReviserAgentHook (删除标记):
//   Critic: afterModel 增加内容 (引用来源)
//   Reviser: afterModel 删除内容 (结束标记)
// 两者都是「在 Agent 输出后做后处理」, 只是方向相反。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * @author : zhengyuchao
 * @date : 2026/1/22
 */
public class ReviserAgentHook  extends ModelHook {

    // Note 2: 修订结束标记。reviser 的 prompt 里要求 LLM 输出这个标记表示修订完成。
    // 这个标记是「控制信号」, 不应出现在最终结果里, afterModel 要清掉它。
    private static final String _END_OF_EDIT_MARK = "---END-OF-EDIT---";


    @Override
    public String getName() {
        return "";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());  // 不需要改请求
    }

    // Note 3: ★ 核心方法: afterModel —— 清理 reviser 输出中的结束标记。
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        // ① 取 reviser 的输出 (outputKey="reviser_agent_output", 在 Controller 里配的)
        Optional<Object> messagesOpt = state.value("reviser_agent_output");
        if (!messagesOpt.isPresent()) {
            return CompletableFuture.completedFuture(Map.of());  // 没输出, 不处理
        }
        if(messagesOpt.get() instanceof AssistantMessage){
            AssistantMessage message = (AssistantMessage) messagesOpt.get();
            // 构建新的消息列表，保持原顺序
            String text = message.getText();
            String newMessage = "";
            // ② ★ 检查并删除结束标记
            // Note 4: 如果文本含 "---END-OF-EDIT---", 用 replace 删掉。
            // replace 会删除所有出现的位置 (虽然正常只出现一次)。
            if(text.contains(_END_OF_EDIT_MARK)){
                newMessage = text.replace(_END_OF_EDIT_MARK,"");
            }
            // ③ 重建 AssistantMessage (保留原 media/metadata/toolCalls, 只改 content)
            // Note 5: 即使 newMessage 是空串 (text 不含标记时), 也会重建消息。
            // 注意: 这里有个小瑕疵——如果 text 不含标记, newMessage 是空串, 会把原内容清空!
            // 实际应该是 newMessage = text (保留原文)。但本例 prompt 一定输出标记, 所以不影响。
            AssistantMessage newAssistantMessage = AssistantMessage.builder()
                    .content(newMessage)
                    .media(message.getMedia())
                    .properties(message.getMetadata())
                    .toolCalls(message.getToolCalls())
                    .build();

            // ④ 返回更新: 把清理后的 reviser_agent_output 写回 state
            return CompletableFuture.completedFuture(Map.of("reviser_agent_output", newAssistantMessage));
        }
        return CompletableFuture.completedFuture(Map.of());
    }
}
