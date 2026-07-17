package com.pi.ai.core.registry;

import com.pi.ai.core.stream.PiAi;
import com.pi.ai.core.types.*;
import com.pi.ai.core.event.AssistantMessageEventStream;

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 属性测试：ApiProvider api 不匹配抛出异常。
 *
 * <p><b>Property 6</b>: 当 model.api 没有已注册的 Provider 时，
 * PiAi.stream 应抛出 IllegalStateException。
 *
 * <p><b>Validates: Requirements 6.8, 9.5</b>
 */
class ApiProviderMismatchPropertyTest {

    @BeforeProperty
    void setUp() {
        ApiProviderRegistry.clear();
    }

    @AfterProperty
    void tearDown() {
        ApiProviderRegistry.clear();
    }

    // ==================== Arbitrary 生成器 ====================

    @Provide
    Arbitrary<String> apiNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    // ==================== 属性测试 ====================

    /**
     * 属性：model.api 没有已注册 Provider 时，PiAi.stream 抛出 IllegalStateException。
     */
    @Property(tries = 200)
    void stream_unregisteredApi_throwsIllegalStateException(@ForAll("apiNames") String api) {
        ApiProviderRegistry.clear();
        Model model = testModel(api);
        Context context = new Context(null, List.of(), null);
        StreamOptions options = StreamOptions.builder().build();

        assertThatThrownBy(() -> PiAi.stream(model, context, options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(api);
    }

    /**
     * 属性：model.api 没有已注册 Provider 时，PiAi.streamSimple 抛出 IllegalStateException。
     */
    @Property(tries = 200)
    void streamSimple_unregisteredApi_throwsIllegalStateException(@ForAll("apiNames") String api) {
        ApiProviderRegistry.clear();
        Model model = testModel(api);
        Context context = new Context(null, List.of(), null);
        SimpleStreamOptions options = SimpleStreamOptions.simpleBuilder().build();

        assertThatThrownBy(() -> PiAi.streamSimple(model, context, options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(api);
    }

    /**
     * 属性：注册了 Provider 后，model.api 匹配时不抛出异常（Provider 正常被调用）。
     */
    @Property(tries = 200)
    void stream_registeredApi_doesNotThrow(@ForAll("apiNames") String api) {
        ApiProviderRegistry.clear();
        // 注册一个返回空 stream 的 stub Provider
        ApiProviderRegistry.register(new ApiProvider() {
            @Override
            public String api() { return api; }

            @Override
            public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
                return new AssistantMessageEventStream();
            }

            @Override
            public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
                return new AssistantMessageEventStream();
            }
        });

        Model model = testModel(api);
        Context context = new Context(null, List.of(), null);
        StreamOptions options = StreamOptions.builder().build();

        // 不应抛出异常
        AssistantMessageEventStream stream = PiAi.stream(model, context, options);
        assertThat(stream).isNotNull();
    }

    /**
     * 属性：注册了 Provider A，但 model.api 为 B（不同），应抛出异常。
     */
    @Property(tries = 200)
    void stream_mismatchedApi_throwsIllegalStateException(
            @ForAll("apiNames") String registeredApi,
            @ForAll("apiNames") String modelApi
    ) {
        Assume.that(!registeredApi.equals(modelApi));
        ApiProviderRegistry.clear();

        ApiProviderRegistry.register(stubProvider(registeredApi));

        Model model = testModel(modelApi);
        Context context = new Context(null, List.of(), null);
        StreamOptions options = StreamOptions.builder().build();

        assertThatThrownBy(() -> PiAi.stream(model, context, options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(modelApi);
    }

    // ==================== 辅助方法 ====================

    private static Model testModel(String api) {
        return new Model(
                "test-model", "Test Model", api, "test-provider",
                "https://api.test.com", false, List.of("text"),
                new ModelCost(1.0, 2.0, 0.5, 0.5), 128000, 4096,
                null, null
        );
    }

    private static ApiProvider stubProvider(String api) {
        return new ApiProvider() {
            @Override
            public String api() { return api; }

            @Override
            public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
                throw new UnsupportedOperationException("stub");
            }
        };
    }
}
