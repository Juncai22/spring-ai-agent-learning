package com.pi.agent.config;

import com.pi.agent.types.ToolExecutionMode;
import com.pi.ai.core.types.CacheRetention;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.ThinkingLevel;
import com.pi.ai.core.types.Transport;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentLoopConfig}.
 *
 * <p><b>Validates: Requirements 13.1, 13.2, 13.3, 13.5, 13.7, 13.8,
 * 13.9, 13.10, 13.11</b>
 */
class AgentLoopConfigTest {

    private static final Model TEST_MODEL = new Model(
            "test-model", "Test Model", "anthropic-messages", "anthropic",
            "https://api.test.com", false, List.of("text"),
            null, 100000, 4096, null, null
    );

    @Test
    void builderCreatesConfigWithDefaults() {
        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(msgs -> Collections.emptyList())
                .build();

        assertThat(config.getModel()).isEqualTo(TEST_MODEL);
        assertThat(config.getConvertToLlm()).isNotNull();
        assertThat(config.getToolExecution()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(config.getTransformContext()).isNull();
        assertThat(config.getGetApiKey()).isNull();
        assertThat(config.getGetSteeringMessages()).isNull();
        assertThat(config.getGetFollowUpMessages()).isNull();
        assertThat(config.getBeforeToolCall()).isNull();
        assertThat(config.getAfterToolCall()).isNull();
    }

    @Test
    void toolExecutionDefaultsToParallel() {
        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(msgs -> Collections.emptyList())
                .build();

        assertThat(config.getToolExecution()).isEqualTo(ToolExecutionMode.PARALLEL);
    }

    @Test
    void toolExecutionCanBeSetToSequential() {
        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(msgs -> Collections.emptyList())
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();

        assertThat(config.getToolExecution()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
    }

    @Test
    void streamOptionsFieldsAreDelegatedCorrectly() {
        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(msgs -> Collections.emptyList())
                .temperature(0.7)
                .maxTokens(4096)
                .apiKey("sk-test")
                .cacheRetention(CacheRetention.SHORT)
                .sessionId("session-1")
                .headers(Map.of("X-Custom", "value"))
                .transport(Transport.SSE)
                .maxRetryDelayMs(5000)
                .metadata(Map.of("key", "val"))
                .reasoning(ThinkingLevel.HIGH)
                .thinkingBudgets(new ThinkingBudgets(null, null, null, 32768))
                .build();

        SimpleStreamOptions opts = config.getStreamOptions();
        assertThat(opts).isNotNull();
        assertThat(opts.getTemperature()).isEqualTo(0.7);
        assertThat(opts.getMaxTokens()).isEqualTo(4096);
        assertThat(opts.getApiKey()).isEqualTo("sk-test");
        assertThat(opts.getCacheRetention()).isEqualTo(CacheRetention.SHORT);
        assertThat(opts.getSessionId()).isEqualTo("session-1");
        assertThat(opts.getHeaders()).containsEntry("X-Custom", "value");
        assertThat(opts.getTransport()).isEqualTo(Transport.SSE);
        assertThat(opts.getMaxRetryDelayMs()).isEqualTo(5000);
        assertThat(opts.getMetadata()).containsEntry("key", "val");
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(opts.getThinkingBudgets()).isNotNull();
    }

    @Test
    void allAgentSpecificFieldsAreSet() {
        ConvertToLlmFunction convertFn = msgs -> Collections.emptyList();
        TransformContextFunction transformFn = (msgs, sig) -> CompletableFuture.completedFuture(msgs);
        GetApiKeyFunction apiKeyFn = provider -> CompletableFuture.completedFuture("key");
        GetSteeringMessagesFunction steeringFn = () -> CompletableFuture.completedFuture(Collections.emptyList());
        GetFollowUpMessagesFunction followUpFn = () -> CompletableFuture.completedFuture(Collections.emptyList());
        BeforeToolCallHook beforeHook = (ctx, sig) -> CompletableFuture.completedFuture(null);
        AfterToolCallHook afterHook = (ctx, sig) -> CompletableFuture.completedFuture(null);

        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(convertFn)
                .transformContext(transformFn)
                .getApiKey(apiKeyFn)
                .getSteeringMessages(steeringFn)
                .getFollowUpMessages(followUpFn)
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .beforeToolCall(beforeHook)
                .afterToolCall(afterHook)
                .build();

        assertThat(config.getModel()).isEqualTo(TEST_MODEL);
        assertThat(config.getConvertToLlm()).isSameAs(convertFn);
        assertThat(config.getTransformContext()).isSameAs(transformFn);
        assertThat(config.getGetApiKey()).isSameAs(apiKeyFn);
        assertThat(config.getGetSteeringMessages()).isSameAs(steeringFn);
        assertThat(config.getGetFollowUpMessages()).isSameAs(followUpFn);
        assertThat(config.getToolExecution()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(config.getBeforeToolCall()).isSameAs(beforeHook);
        assertThat(config.getAfterToolCall()).isSameAs(afterHook);
    }

    @Test
    void signalAndOnPayloadAreDelegatedToStreamOptions() {
        CancellationSignal signal = new CancellationSignal();

        AgentLoopConfig config = AgentLoopConfig.builder()
                .model(TEST_MODEL)
                .convertToLlm(msgs -> Collections.emptyList())
                .signal(signal)
                .onPayload((payload, model) -> null)
                .build();

        assertThat(config.getStreamOptions().getSignal()).isSameAs(signal);
        assertThat(config.getStreamOptions().getOnPayload()).isNotNull();
    }

    @Test
    void modelAndConvertToLlmCanBeNull() {
        // model and convertToLlm can be null in the builder;
        // validated at usage time by Agent class
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        assertThat(config.getModel()).isNull();
        assertThat(config.getConvertToLlm()).isNull();
        assertThat(config.getToolExecution()).isEqualTo(ToolExecutionMode.PARALLEL);
    }
}
