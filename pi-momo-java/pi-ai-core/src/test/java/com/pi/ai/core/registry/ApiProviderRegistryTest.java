package com.pi.ai.core.registry;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StreamOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * ApiProviderRegistry 单元测试。
 *
 * <p>验证：注册/获取 round-trip、同 api 覆盖、getAll、按 sourceId 注销、
 * clear、向后兼容（无 sourceId 注册）、线程安全。
 */
class ApiProviderRegistryTest {

    @BeforeEach
    void setUp() {
        ApiProviderRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        ApiProviderRegistry.clear();
    }

    // --- register 和 get round-trip ---

    @Test
    void register_and_get_roundTrip() {
        var provider = stubProvider("api-a");
        ApiProviderRegistry.register(provider, "src1");

        assertThat(ApiProviderRegistry.get("api-a")).isSameAs(provider);
    }

    @Test
    void register_withoutSourceId_usesDefault() {
        // 向后兼容：register(provider) 不指定 sourceId
        var provider = stubProvider("api-b");
        ApiProviderRegistry.register(provider);

        assertThat(ApiProviderRegistry.get("api-b")).isSameAs(provider);
    }

    // --- 同 api 覆盖 ---

    @Test
    void register_sameApi_overwritesOldProvider() {
        var old = stubProvider("api-x");
        var newer = stubProvider("api-x");
        ApiProviderRegistry.register(old, "src1");
        ApiProviderRegistry.register(newer, "src2");

        assertThat(ApiProviderRegistry.get("api-x")).isSameAs(newer);
    }

    // --- get 未注册返回 null ---

    @Test
    void get_unregisteredApi_returnsNull() {
        assertThat(ApiProviderRegistry.get("nonexistent")).isNull();
    }

    // --- getAll ---

    @Test
    void getAll_returnsAllRegisteredProviders() {
        var p1 = stubProvider("api-1");
        var p2 = stubProvider("api-2");
        var p3 = stubProvider("api-3");
        ApiProviderRegistry.register(p1, "src1");
        ApiProviderRegistry.register(p2, "src1");
        ApiProviderRegistry.register(p3, "src2");

        List<ApiProvider> all = ApiProviderRegistry.getAll();
        assertThat(all).containsExactlyInAnyOrder(p1, p2, p3);
    }

    @Test
    void getAll_emptyRegistry_returnsEmptyList() {
        assertThat(ApiProviderRegistry.getAll()).isEmpty();
    }

    // --- unregister(sourceId) ---

    @Test
    void unregister_removesAllProvidersWithMatchingSourceId() {
        var p1 = stubProvider("api-1");
        var p2 = stubProvider("api-2");
        var p3 = stubProvider("api-3");
        ApiProviderRegistry.register(p1, "plugin-a");
        ApiProviderRegistry.register(p2, "plugin-a");
        ApiProviderRegistry.register(p3, "plugin-b");

        ApiProviderRegistry.unregister("plugin-a");

        assertThat(ApiProviderRegistry.get("api-1")).isNull();
        assertThat(ApiProviderRegistry.get("api-2")).isNull();
        assertThat(ApiProviderRegistry.get("api-3")).isSameAs(p3);
    }

    @Test
    void unregister_nonexistentSourceId_doesNothing() {
        var p1 = stubProvider("api-1");
        ApiProviderRegistry.register(p1, "src1");

        ApiProviderRegistry.unregister("no-such-source");

        assertThat(ApiProviderRegistry.get("api-1")).isSameAs(p1);
    }

    @Test
    void unregister_defaultSourceId_removesDefaultRegistered() {
        // 通过 register(provider) 注册的使用 "default" sourceId
        var p1 = stubProvider("api-1");
        var p2 = stubProvider("api-2");
        ApiProviderRegistry.register(p1);          // sourceId = "default"
        ApiProviderRegistry.register(p2, "custom");

        ApiProviderRegistry.unregister("default");

        assertThat(ApiProviderRegistry.get("api-1")).isNull();
        assertThat(ApiProviderRegistry.get("api-2")).isSameAs(p2);
    }

    // --- clear ---

    @Test
    void clear_removesAllProviders() {
        ApiProviderRegistry.register(stubProvider("api-1"), "src1");
        ApiProviderRegistry.register(stubProvider("api-2"), "src2");

        ApiProviderRegistry.clear();

        assertThat(ApiProviderRegistry.getAll()).isEmpty();
        assertThat(ApiProviderRegistry.get("api-1")).isNull();
        assertThat(ApiProviderRegistry.get("api-2")).isNull();
    }

    // --- 线程安全 ---

    @Test
    void concurrentRegisterAndGet_isThreadSafe() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 并发注册不同 api 的 Provider
        for (int i = 0; i < threadCount; i++) {
            final String api = "api-" + i;
            executor.submit(() -> {
                try {
                    ApiProviderRegistry.register(stubProvider(api), "src");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证所有 Provider 都已注册
        assertThat(ApiProviderRegistry.getAll()).hasSize(threadCount);
        for (int i = 0; i < threadCount; i++) {
            assertThat(ApiProviderRegistry.get("api-" + i)).isNotNull();
        }
    }

    // --- Stub Provider 工厂 ---

    /**
     * 创建一个最小化的 stub ApiProvider。
     */
    private static ApiProvider stubProvider(String api) {
        return new ApiProvider() {
            @Override
            public String api() {
                return api;
            }

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
