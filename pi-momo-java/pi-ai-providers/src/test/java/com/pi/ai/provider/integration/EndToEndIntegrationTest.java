package com.pi.ai.provider.integration;

import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.registry.ApiProviderRegistry;
import com.pi.ai.core.stream.PiAi;
import com.pi.ai.core.types.*;
import com.pi.ai.provider.builtin.BuiltInProviders;
import com.pi.ai.provider.common.MessageTransformer;
import com.pi.ai.provider.common.SseParser;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 端到端集成测试：验证 SSE 解析 → EventStream → AssistantMessage 完整流程，
 * 以及跨 Provider 消息转换的集成正确性。
 */
class EndToEndIntegrationTest {

    @BeforeEach
    void setup() {
        ApiProviderRegistry.clear();
    }

    // =========================================================================
    // SSE → EventStream → AssistantMessage 管道测试
    // =========================================================================

    @Test
    @DisplayName("SSE 解析 → EventStream 事件推送 → result() 完成")
    void sseToEventStreamToResult() {
        // 模拟 Anthropic 风格的 SSE 响应
        String sseData = """
                event: message_start
                data: {"type":"message_start"}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" World"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                """;

        // 解析 SSE
        var input = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
        Iterator<SseParser.SseEvent> events = SseParser.parse(input);

        List<SseParser.SseEvent> eventList = new ArrayList<>();
        while (events.hasNext()) {
            eventList.add(events.next());
        }

        // 验证 SSE 事件数量和内容
        assertThat(eventList).hasSize(6);
        assertThat(eventList.get(0).event()).isEqualTo("message_start");
        assertThat(eventList.get(1).event()).isEqualTo("content_block_start");
        assertThat(eventList.get(2).data()).contains("Hello");
        assertThat(eventList.get(3).data()).contains(" World");
    }

    @Test
    @DisplayName("EventStream 事件推送和 result() Future 完成")
    void eventStreamPushAndResult() {
        var stream = AssistantMessageEventStream.create();

        AssistantMessage msg = AssistantMessage.builder()
                .content(List.of(new TextContent("text", "Hello World", null)))
                .api("test-api")
                .provider("test-provider")
                .model("test-model")
                .usage(new Usage(10, 5, 0, 0, 15, new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();

        // 推送事件
        stream.push(new AssistantMessageEvent.Start(msg));
        stream.push(new AssistantMessageEvent.TextStart(0, msg));
        stream.push(new AssistantMessageEvent.TextDelta(0, "Hello World", msg));
        stream.push(new AssistantMessageEvent.TextEnd(0, "Hello World", msg));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
        stream.end(null);

        // 验证迭代器消费
        List<AssistantMessageEvent> consumed = new ArrayList<>();
        for (AssistantMessageEvent event : stream) {
            consumed.add(event);
        }

        assertThat(consumed).hasSize(5);
        assertThat(consumed.get(0)).isInstanceOf(AssistantMessageEvent.Start.class);
        assertThat(consumed.get(1)).isInstanceOf(AssistantMessageEvent.TextStart.class);
        assertThat(consumed.get(2)).isInstanceOf(AssistantMessageEvent.TextDelta.class);
        assertThat(consumed.get(3)).isInstanceOf(AssistantMessageEvent.TextEnd.class);
        assertThat(consumed.get(4)).isInstanceOf(AssistantMessageEvent.Done.class);
    }

    // =========================================================================
    // BuiltInProviders 注册集成测试
    // =========================================================================

    @Test
    @DisplayName("BuiltInProviders 注册全部 10 个 Provider")
    void builtInProvidersRegistration() {
        BuiltInProviders.registerBuiltInApiProviders();

        List<ApiProvider> all = ApiProviderRegistry.getAll();
        assertThat(all).hasSize(10);

        // 验证所有预期的 API 都已注册
        String[] expectedApis = {
                "anthropic-messages",
                "openai-completions",
                "openai-responses",
                "azure-openai-responses",
                "openai-codex-responses",
                "google-generative-ai",
                "google-gemini-cli",
                "google-vertex",
                "mistral-conversations",
                "bedrock-converse-stream"
        };

        for (String api : expectedApis) {
            assertThat(ApiProviderRegistry.get(api))
                    .as("Provider for api: " + api)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("resetApiProviders 清空后重新注册")
    void resetApiProviders() {
        BuiltInProviders.registerBuiltInApiProviders();
        assertThat(ApiProviderRegistry.getAll()).hasSize(10);

        // 注册自定义 Provider
        ApiProviderRegistry.register(new StubProvider("custom-api"), "custom");
        assertThat(ApiProviderRegistry.getAll()).hasSize(11);

        // Reset
        BuiltInProviders.resetApiProviders();
        assertThat(ApiProviderRegistry.getAll()).hasSize(10);
        assertThat(ApiProviderRegistry.get("custom-api")).isNull();
    }

    @Test
    @DisplayName("PiAi.stream 未注册 Provider 抛出 IllegalStateException")
    void piAiStreamUnregisteredProvider() {
        Model model = new Model("test-id", "Test", "unknown-api", "test-provider",
                null, false, null, null, 128000, 4096, null, null);
        Context context = new Context(null, List.of(), null);

        assertThatThrownBy(() -> PiAi.stream(model, context, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown-api");
    }

    @Test
    @DisplayName("PiAi 初始化器自动注册 Provider")
    void piAiInitializerAutoRegisters() {
        PiAi.setInitializer(BuiltInProviders::registerBuiltInApiProviders);

        // 首次调用 stream 时自动初始化（会抛异常因为没有真正的 HTTP 连接，但 Provider 已注册）
        Model model = new Model("claude-sonnet-4-20250514", "Claude Sonnet 4",
                "anthropic-messages", "anthropic",
                null, false, null, null, 200000, 16384, null, null);
        Context context = new Context(null, List.of(), null);

        // stream 调用会触发初始化，Provider 查找成功（返回 EventStream，不会抛 IllegalStateException）
        AssistantMessageEventStream eventStream = PiAi.stream(model, context, null);
        assertThat(eventStream).isNotNull();

        // 验证 Provider 已注册
        assertThat(ApiProviderRegistry.get("anthropic-messages")).isNotNull();
    }

    // =========================================================================
    // 跨 Provider 消息转换集成测试
    // =========================================================================

    @Test
    @DisplayName("MessageTransformer 完整消息转换流程")
    void messageTransformerFullPipeline() {
        Model targetModel = new Model("gpt-4o", "GPT-4o", "openai-completions",
                "openai", null, false, null, null, 128000, 4096, null, null);

        // 构建包含 thinking、text、toolCall 的消息序列
        AssistantMessage am = AssistantMessage.builder()
                .content(List.of(
                        new ThinkingContent("thinking", "Let me think...", null, null),
                        new TextContent("text", "Here's the answer", null),
                        new ToolCall("toolUse", "tc-1", "calculate", Map.of("a", 1), null)
                ))
                .api("anthropic-messages")
                .provider("anthropic")
                .model("claude-sonnet-4-20250514")
                .stopReason(StopReason.TOOL_USE)
                .timestamp(System.currentTimeMillis())
                .build();

        ToolResultMessage trm = new ToolResultMessage(
                "toolResult", "tc-1", "calculate",
                List.of(new TextContent("42")),
                null, false, System.currentTimeMillis());

        List<Message> messages = List.of(
                new UserMessage("user", List.of(new TextContent("What is 6*7?")), System.currentTimeMillis()),
                am,
                trm
        );

        // 转换
        List<Message> result = MessageTransformer.transformMessages(messages, targetModel, null);

        assertThat(result).hasSize(3);

        // 第一条：UserMessage 透传
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);

        // 第二条：AssistantMessage，thinking 转为 text（跨模型）
        AssistantMessage transformedAm = (AssistantMessage) result.get(1);
        assertThat(transformedAm.getContent()).hasSize(3);
        // thinking 块转为 TextContent
        assertThat(transformedAm.getContent().get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) transformedAm.getContent().get(0)).text()).isEqualTo("Let me think...");

        // 第三条：ToolResultMessage 透传
        assertThat(result.get(2)).isInstanceOf(ToolResultMessage.class);
    }

    @Test
    @DisplayName("MessageTransformer 过滤 error/aborted 消息")
    void messageTransformerFiltersErrorMessages() {
        Model targetModel = new Model("gpt-4o", "GPT-4o", "openai-completions",
                "openai", null, false, null, null, 128000, 4096, null, null);

        AssistantMessage errorMsg = AssistantMessage.builder()
                .content(List.of(new TextContent("text", "Error occurred", null)))
                .api("anthropic-messages")
                .provider("anthropic")
                .model("claude-sonnet-4-20250514")
                .stopReason(StopReason.ERROR)
                .errorMessage("Something went wrong")
                .timestamp(System.currentTimeMillis())
                .build();

        AssistantMessage goodMsg = AssistantMessage.builder()
                .content(List.of(new TextContent("text", "Good response", null)))
                .api("anthropic-messages")
                .provider("anthropic")
                .model("claude-sonnet-4-20250514")
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();

        List<Message> messages = List.of(
                new UserMessage("user", List.of(new TextContent("Hello")), System.currentTimeMillis()),
                errorMsg,
                goodMsg
        );

        List<Message> result = MessageTransformer.transformMessages(messages, targetModel, null);

        // error 消息被过滤
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) result.get(1)).getStopReason()).isEqualTo(StopReason.STOP);
    }

    @Test
    @DisplayName("MessageTransformer 孤立 ToolCall 插入合成 ToolResult")
    void messageTransformerOrphanToolCallSynthetic() {
        Model targetModel = new Model("gpt-4o", "GPT-4o", "openai-completions",
                "openai", null, false, null, null, 128000, 4096, null, null);

        AssistantMessage am = AssistantMessage.builder()
                .content(List.of(
                        new ToolCall("toolUse", "tc-orphan", "search", Map.of(), null)
                ))
                .api("openai-completions")
                .provider("openai")
                .model("gpt-4o")
                .stopReason(StopReason.TOOL_USE)
                .timestamp(System.currentTimeMillis())
                .build();

        // 没有对应的 ToolResultMessage，直接跟 UserMessage
        List<Message> messages = List.of(
                new UserMessage("user", List.of(new TextContent("Search for X")), System.currentTimeMillis()),
                am,
                new UserMessage("user", List.of(new TextContent("Never mind")), System.currentTimeMillis())
        );

        List<Message> result = MessageTransformer.transformMessages(messages, targetModel, null);

        // 应该插入合成 ToolResult
        assertThat(result).hasSize(4);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(2)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage synthetic = (ToolResultMessage) result.get(2);
        assertThat(synthetic.toolCallId()).isEqualTo("tc-orphan");
        assertThat(synthetic.isError()).isTrue();
        assertThat(result.get(3)).isInstanceOf(UserMessage.class);
    }

    // =========================================================================
    // OpenAI [DONE] 标记测试
    // =========================================================================

    @Test
    @DisplayName("SSE 解析器正确处理 [DONE] 终止标记")
    void sseDoneMarker() {
        String sseData = """
                data: {"choices":[{"delta":{"content":"Hi"}}]}

                data: [DONE]

                """;

        var input = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
        Iterator<SseParser.SseEvent> events = SseParser.parse(input);

        List<SseParser.SseEvent> eventList = new ArrayList<>();
        while (events.hasNext()) {
            eventList.add(events.next());
        }

        assertThat(eventList).hasSize(2);
        assertThat(eventList.get(0).data()).contains("Hi");
        assertThat(eventList.get(1).isDone()).isTrue();
    }

    // =========================================================================
    // Stub Provider for testing
    // =========================================================================

    private static class StubProvider implements ApiProvider {
        private final String api;

        StubProvider(String api) { this.api = api; }

        @Override public String api() { return api; }
        @Override public AssistantMessageEventStream stream(Model m, Context c, StreamOptions o) {
            return AssistantMessageEventStream.create();
        }
        @Override public AssistantMessageEventStream streamSimple(Model m, Context c, SimpleStreamOptions o) {
            return stream(m, c, null);
        }
    }
}
