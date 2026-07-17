package com.pi.ai.core.registry;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StreamOptions;

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：ApiProviderRegistry register/get round-trip。
 *
 * <p><b>Property 5</b>: 验证注册、覆盖、按 sourceId 注销、清空的正确性。
 *
 * <p><b>Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 6.7</b>
 */
class ApiProviderRegistryPropertyTest {

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

    @Provide
    Arbitrary<String> sourceIds() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<List<String>> apiNameLists() {
        return apiNames().list().ofMinSize(1).ofMaxSize(30).uniqueElements();
    }

    // ==================== 属性测试 ====================

    /**
     * 属性：注册后 get 返回同一实例。
     *
     * <p>对任意 api 名称，注册一个 Provider 后通过 get(api) 应返回该实例。
     */
    @Property(tries = 200)
    void registerThenGet_returnsSameInstance(@ForAll("apiNames") String api, @ForAll("sourceIds") String sourceId) {
        var provider = stubProvider(api);
        ApiProviderRegistry.register(provider, sourceId);

        assertThat(ApiProviderRegistry.get(api)).isSameAs(provider);
    }

    /**
     * 属性：同一 api 注册新 Provider 覆盖旧 Provider。
     *
     * <p>对任意 api 名称，先后注册两个 Provider，get 应返回后注册的实例。
     */
    @Property(tries = 200)
    void registerSameApi_overwritesOldProvider(@ForAll("apiNames") String api) {
        var old = stubProvider(api);
        var newer = stubProvider(api);
        ApiProviderRegistry.register(old, "src1");
        ApiProviderRegistry.register(newer, "src2");

        assertThat(ApiProviderRegistry.get(api)).isSameAs(newer);
    }

    /**
     * 属性：批量注册后 getAll 返回所有 Provider。
     *
     * <p>对任意不重复的 api 名称列表，全部注册后 getAll 的大小应等于列表大小。
     */
    @Property(tries = 100)
    void registerMultiple_getAllReturnsAll(@ForAll("apiNameLists") List<String> apis) {
        ApiProviderRegistry.clear();
        for (String api : apis) {
            ApiProviderRegistry.register(stubProvider(api), "src");
        }

        List<ApiProvider> all = ApiProviderRegistry.getAll();
        assertThat(all).hasSize(apis.size());

        Set<String> registeredApis = all.stream().map(ApiProvider::api).collect(Collectors.toSet());
        assertThat(registeredApis).containsExactlyInAnyOrderElementsOf(apis);
    }

    /**
     * 属性：unregister(sourceId) 移除该 sourceId 的所有 Provider，保留其他。
     *
     * <p>注册两组不同 sourceId 的 Provider，注销其中一组后，
     * 该组的 Provider 不可获取，另一组仍可获取。
     */
    @Property(tries = 100)
    void unregister_removesOnlyMatchingSourceId(
            @ForAll("apiNameLists") List<String> apis,
            @ForAll("sourceIds") String sourceToRemove,
            @ForAll("sourceIds") String sourceToKeep
    ) {
        Assume.that(!sourceToRemove.equals(sourceToKeep));
        Assume.that(apis.size() >= 2);
        ApiProviderRegistry.clear();

        // 前半部分用 sourceToRemove，后半部分用 sourceToKeep
        int mid = apis.size() / 2;
        List<String> toRemoveApis = apis.subList(0, mid);
        List<String> toKeepApis = apis.subList(mid, apis.size());

        for (String api : toRemoveApis) {
            ApiProviderRegistry.register(stubProvider(api), sourceToRemove);
        }
        for (String api : toKeepApis) {
            ApiProviderRegistry.register(stubProvider(api), sourceToKeep);
        }

        ApiProviderRegistry.unregister(sourceToRemove);

        // 被注销的 Provider 不可获取
        for (String api : toRemoveApis) {
            assertThat(ApiProviderRegistry.get(api)).isNull();
        }
        // 保留的 Provider 仍可获取
        for (String api : toKeepApis) {
            assertThat(ApiProviderRegistry.get(api)).isNotNull();
        }
    }

    /**
     * 属性：clear() 后所有 Provider 不可获取。
     *
     * <p>对任意 api 名称列表，全部注册后 clear，getAll 应为空，get 应返回 null。
     */
    @Property(tries = 100)
    void clear_removesAllProviders(@ForAll("apiNameLists") List<String> apis) {
        for (String api : apis) {
            ApiProviderRegistry.register(stubProvider(api), "src");
        }

        ApiProviderRegistry.clear();

        assertThat(ApiProviderRegistry.getAll()).isEmpty();
        for (String api : apis) {
            assertThat(ApiProviderRegistry.get(api)).isNull();
        }
    }

    /**
     * 属性：未注册的 api 返回 null。
     */
    @Property(tries = 200)
    void get_unregisteredApi_returnsNull(@ForAll("apiNames") String api) {
        ApiProviderRegistry.clear();
        assertThat(ApiProviderRegistry.get(api)).isNull();
    }

    // ==================== Stub Provider 工厂 ====================

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
