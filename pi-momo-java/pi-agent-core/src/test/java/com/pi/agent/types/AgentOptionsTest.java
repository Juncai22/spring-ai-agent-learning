package com.pi.agent.types;

import com.pi.agent.config.AfterToolCallHook;
import com.pi.agent.config.BeforeToolCallHook;
import com.pi.agent.config.ConvertToLlmFunction;
import com.pi.agent.config.GetApiKeyFunction;
import com.pi.agent.config.StreamFn;
import com.pi.agent.config.TransformContextFunction;
import com.pi.ai.core.types.PayloadInterceptor;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.Transport;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentOptions}.
 *
 * <p><b>Validates: Requirements 23.1, 23.2</b>
 */
class AgentOptionsTest {

    @Test
    void builderCreatesEmptyOptionsWithAllNulls() {
        AgentOptions opts = AgentOptions.builder().build();

        assertThat(opts.getInitialState()).isNull();
        assertThat(opts.getConvertToLlm()).isNull();
        assertThat(opts.getTransformContext()).isNull();
        assertThat(opts.getSteeringMode()).isNull();
        assertThat(opts.getFollowUpMode()).isNull();
        assertThat(opts.getStreamFn()).isNull();
        assertThat(opts.getSessionId()).isNull();
        assertThat(opts.getGetApiKey()).isNull();
        assertThat(opts.getOnPayload()).isNull();
        assertThat(opts.getThinkingBudgets()).isNull();
        assertThat(opts.getTransport()).isNull();
        assertThat(opts.getMaxRetryDelayMs()).isNull();
        assertThat(opts.getToolExecution()).isNull();
        assertThat(opts.getBeforeToolCall()).isNull();
        assertThat(opts.getAfterToolCall()).isNull();
    }

    @Test
    void allFieldsCanBeSet() {
        AgentState state = new AgentState();
        ConvertToLlmFunction convertFn = msgs -> Collections.emptyList();
        TransformContextFunction transformFn = (msgs, sig) -> CompletableFuture.completedFuture(msgs);
        StreamFn streamFn = (model, ctx, options) -> null;
        GetApiKeyFunction apiKeyFn = provider -> CompletableFuture.completedFuture("key");
        PayloadInterceptor payloadFn = (payload, model) -> null;
        ThinkingBudgets budgets = new ThinkingBudgets(100, 200, 300, 400);
        BeforeToolCallHook beforeHook = (ctx, sig) -> CompletableFuture.completedFuture(null);
        AfterToolCallHook afterHook = (ctx, sig) -> CompletableFuture.completedFuture(null);

        AgentOptions opts = AgentOptions.builder()
                .initialState(state)
                .convertToLlm(convertFn)
                .transformContext(transformFn)
                .steeringMode(QueueMode.ALL)
                .followUpMode(QueueMode.ONE_AT_A_TIME)
                .streamFn(streamFn)
                .sessionId("sess-123")
                .getApiKey(apiKeyFn)
                .onPayload(payloadFn)
                .thinkingBudgets(budgets)
                .transport(Transport.SSE)
                .maxRetryDelayMs(30000)
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .beforeToolCall(beforeHook)
                .afterToolCall(afterHook)
                .build();

        assertThat(opts.getInitialState()).isSameAs(state);
        assertThat(opts.getConvertToLlm()).isSameAs(convertFn);
        assertThat(opts.getTransformContext()).isSameAs(transformFn);
        assertThat(opts.getSteeringMode()).isEqualTo(QueueMode.ALL);
        assertThat(opts.getFollowUpMode()).isEqualTo(QueueMode.ONE_AT_A_TIME);
        assertThat(opts.getStreamFn()).isSameAs(streamFn);
        assertThat(opts.getSessionId()).isEqualTo("sess-123");
        assertThat(opts.getGetApiKey()).isSameAs(apiKeyFn);
        assertThat(opts.getOnPayload()).isSameAs(payloadFn);
        assertThat(opts.getThinkingBudgets()).isSameAs(budgets);
        assertThat(opts.getTransport()).isEqualTo(Transport.SSE);
        assertThat(opts.getMaxRetryDelayMs()).isEqualTo(30000);
        assertThat(opts.getToolExecution()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(opts.getBeforeToolCall()).isSameAs(beforeHook);
        assertThat(opts.getAfterToolCall()).isSameAs(afterHook);
    }

    @Test
    void transportCanBeWebsocketOrAuto() {
        AgentOptions wsOpts = AgentOptions.builder()
                .transport(Transport.WEBSOCKET)
                .build();
        assertThat(wsOpts.getTransport()).isEqualTo(Transport.WEBSOCKET);

        AgentOptions autoOpts = AgentOptions.builder()
                .transport(Transport.AUTO)
                .build();
        assertThat(autoOpts.getTransport()).isEqualTo(Transport.AUTO);
    }

    @Test
    void queueModesCanBeBothAll() {
        AgentOptions opts = AgentOptions.builder()
                .steeringMode(QueueMode.ALL)
                .followUpMode(QueueMode.ALL)
                .build();

        assertThat(opts.getSteeringMode()).isEqualTo(QueueMode.ALL);
        assertThat(opts.getFollowUpMode()).isEqualTo(QueueMode.ALL);
    }
}
