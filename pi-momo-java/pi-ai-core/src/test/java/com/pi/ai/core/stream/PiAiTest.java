package com.pi.ai.core.stream;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.registry.ApiProviderRegistry;
import com.pi.ai.core.types.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

/**
 * PiAi 门面类单元测试。
 *
 * <p>使用 stub ApiProvider 验证：
 * <ul>
 *   <li>stream/streamSimple 正确委托到已注册的 Provider</li>
 *   <li>complete/completeSimple 返回 stream 的 result() Future</li>
 *   <li>未注册 Provider 时抛出 IllegalStateException</li>
 * </ul>
 */
class PiAiTest {

    /** 测试用 API 标识 */
    private static final String TEST_API = "test-api";

    /** 测试用 Model */
    private static final Model TEST_MODEL = new Model(
            "test-model-id",
            "Test Model",
            TEST_API,
            "test-provider",
            "https://api.test.com",
            false,
            List.of("text"),
            new ModelCost(1.0, 2.0, 0.5, 0.5),
            128000,
            4096,
            null,
            null
    );

    /** 测试用 Context */
    private static final Context TEST_CONTEXT = new Context(List.of());

    /** 测试用 StreamOptions */
    private static final StreamOptions TEST_OPTIONS = StreamOptions.builder().build();

    /** 测试用 SimpleStreamOptions */
    private static final SimpleStreamOptions TEST_SIMPLE_OPTIONS = SimpleStreamOptions.simpleBuilder().build();

    /** 预构建的最终 AssistantMessage，用于 stub Provider 返回 */
    private static final AssistantMessage FINAL_MESSAGE = AssistantMessage.builder()
            .api(TEST_API)
            .provider("test-provider")
            .model("test-model-id")
            .stopReason(StopReason.STOP)
            .build();

    @BeforeEach
    void setUp() {
        // 每个测试前清空注册表，确保隔离
        ApiProviderRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        ApiProviderRegistry.clear();
    }

    // --- stream 委托测试 ---

    @Test
    void stream_shouldDelegateToRegisteredProvider() {
        // 注册 stub Provider
        StubApiProvider stub = new StubApiProvider(TEST_API);
        ApiProviderRegistry.register(stub);

        // 调用 PiAi.stream
        AssistantMessageEventStream result = PiAi.stream(TEST_MODEL, TEST_CONTEXT, TEST_OPTIONS);

        // 验证返回的是 stub 创建的 stream
        assertThat(result).isNotNull();
        assertThat(stub.streamCalled).isTrue();
        assertThat(stub.streamSimpleCalled).isFalse();
    }

    // --- streamSimple 委托测试 ---

    @Test
    void streamSimple_shouldDelegateToRegisteredProvider() {
        StubApiProvider stub = new StubApiProvider(TEST_API);
        ApiProviderRegistry.register(stub);

        AssistantMessageEventStream result = PiAi.streamSimple(TEST_MODEL, TEST_CONTEXT, TEST_SIMPLE_OPTIONS);

        assertThat(result).isNotNull();
        assertThat(stub.streamSimpleCalled).isTrue();
        assertThat(stub.streamCalled).isFalse();
    }

    // --- complete 返回 result() Future ---

    @Test
    void complete_shouldReturnResultFutureFromStream() {
        StubApiProvider stub = new StubApiProvider(TEST_API);
        ApiProviderRegistry.register(stub);

        CompletableFuture<AssistantMessage> future = PiAi.complete(TEST_MODEL, TEST_CONTEXT, TEST_OPTIONS);

        assertThat(future).isNotNull();
        // stub 的 stream 会 push done 事件，result() 应该已完成
        AssistantMessage msg = future.join();
        assertThat(msg).isEqualTo(FINAL_MESSAGE);
    }

    // --- completeSimple 返回 result() Future ---

    @Test
    void completeSimple_shouldReturnResultFutureFromStreamSimple() {
        StubApiProvider stub = new StubApiProvider(TEST_API);
        ApiProviderRegistry.register(stub);

        CompletableFuture<AssistantMessage> future = PiAi.completeSimple(TEST_MODEL, TEST_CONTEXT, TEST_SIMPLE_OPTIONS);

        assertThat(future).isNotNull();
        AssistantMessage msg = future.join();
        assertThat(msg).isEqualTo(FINAL_MESSAGE);
    }

    // --- 未注册 Provider 抛出异常 ---

    @Test
    void stream_shouldThrowWhenProviderNotRegistered() {
        assertThatThrownBy(() -> PiAi.stream(TEST_MODEL, TEST_CONTEXT, TEST_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API provider registered for api: " + TEST_API);
    }

    @Test
    void streamSimple_shouldThrowWhenProviderNotRegistered() {
        assertThatThrownBy(() -> PiAi.streamSimple(TEST_MODEL, TEST_CONTEXT, TEST_SIMPLE_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_API);
    }

    @Test
    void complete_shouldThrowWhenProviderNotRegistered() {
        assertThatThrownBy(() -> PiAi.complete(TEST_MODEL, TEST_CONTEXT, TEST_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_API);
    }

    @Test
    void completeSimple_shouldThrowWhenProviderNotRegistered() {
        assertThatThrownBy(() -> PiAi.completeSimple(TEST_MODEL, TEST_CONTEXT, TEST_SIMPLE_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_API);
    }

    // --- Stub ApiProvider 实现 ---

    /**
     * 测试用 stub Provider，记录调用状态并返回预构建的事件流。
     */
    private static class StubApiProvider implements ApiProvider {

        private final String apiId;
        boolean streamCalled = false;
        boolean streamSimpleCalled = false;

        StubApiProvider(String apiId) {
            this.apiId = apiId;
        }

        @Override
        public String api() {
            return apiId;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
            streamCalled = true;
            return createCompletedStream();
        }

        @Override
        public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
            streamSimpleCalled = true;
            return createCompletedStream();
        }

        /**
         * 创建一个已完成的事件流，push done 事件并结束。
         */
        private AssistantMessageEventStream createCompletedStream() {
            var stream = AssistantMessageEventStream.create();
            stream.push(new com.pi.ai.core.event.AssistantMessageEvent.Done(StopReason.STOP, FINAL_MESSAGE));
            stream.end();
            return stream;
        }
    }
}
