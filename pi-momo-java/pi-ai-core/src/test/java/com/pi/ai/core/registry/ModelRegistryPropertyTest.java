package com.pi.ai.core.registry;

import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ModelCost;

import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：supportsXhigh 和 modelsAreEqual。
 *
 * <p><b>Property 8</b>: supportsXhigh 在且仅在 model.id 包含特定子串时返回 true。
 * <p><b>Property 9</b>: modelsAreEqual 在且仅在 id 和 provider 都相等时返回 true。
 *
 * <p><b>Validates: Requirements 8.6, 8.7</b>
 */
class ModelRegistryPropertyTest {

    private static final ModelCost DEFAULT_COST = new ModelCost(1.0, 2.0, 0.5, 0.5);

    // ==================== Arbitrary 生成器 ====================

    /**
     * 生成不包含 xhigh 关键子串的普通 model id。
     */
    @Provide
    Arbitrary<String> nonXhighIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(30)
                .filter(id -> !id.contains("gpt-5.2") && !id.contains("gpt-5.3")
                        && !id.contains("gpt-5.4") && !id.contains("opus-4-6")
                        && !id.contains("opus-4.6"));
    }

    /**
     * 生成包含 xhigh 关键子串的 model id。
     */
    @Provide
    Arbitrary<String> xhighIds() {
        Arbitrary<String> prefixes = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(10);
        Arbitrary<String> suffixes = Arbitraries.strings().alpha().numeric().ofMinLength(0).ofMaxLength(10);
        Arbitrary<String> markers = Arbitraries.of("gpt-5.2", "gpt-5.3", "gpt-5.4", "opus-4-6", "opus-4.6");
        return Combinators.combine(prefixes, markers, suffixes).as((p, m, s) -> p + m + s);
    }

    @Provide
    Arbitrary<String> modelIds() {
        return Arbitraries.strings().alpha().numeric().withChars('-', '.', '_').ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> providerNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15);
    }

    // ==================== Property 8: supportsXhigh ====================

    /**
     * 属性：包含 xhigh 关键子串的 model id，supportsXhigh 返回 true。
     */
    @Property(tries = 500)
    void supportsXhigh_withXhighId_returnsTrue(@ForAll("xhighIds") String id) {
        Model model = testModel(id, "test-provider");
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    /**
     * 属性：不包含 xhigh 关键子串的 model id，supportsXhigh 返回 false。
     */
    @Property(tries = 500)
    void supportsXhigh_withNonXhighId_returnsFalse(@ForAll("nonXhighIds") String id) {
        Model model = testModel(id, "test-provider");
        assertThat(ModelRegistry.supportsXhigh(model)).isFalse();
    }

    // ==================== Property 9: modelsAreEqual ====================

    /**
     * 属性：id 和 provider 都相同时，modelsAreEqual 返回 true。
     */
    @Property(tries = 500)
    void modelsAreEqual_sameIdAndProvider_returnsTrue(
            @ForAll("modelIds") String id,
            @ForAll("providerNames") String provider
    ) {
        Model a = testModel(id, provider);
        Model b = testModel(id, provider);
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isTrue();
    }

    /**
     * 属性：id 不同时，modelsAreEqual 返回 false。
     */
    @Property(tries = 500)
    void modelsAreEqual_differentId_returnsFalse(
            @ForAll("modelIds") String idA,
            @ForAll("modelIds") String idB,
            @ForAll("providerNames") String provider
    ) {
        Assume.that(!idA.equals(idB));
        Model a = testModel(idA, provider);
        Model b = testModel(idB, provider);
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isFalse();
    }

    /**
     * 属性：provider 不同时，modelsAreEqual 返回 false。
     */
    @Property(tries = 500)
    void modelsAreEqual_differentProvider_returnsFalse(
            @ForAll("modelIds") String id,
            @ForAll("providerNames") String providerA,
            @ForAll("providerNames") String providerB
    ) {
        Assume.that(!providerA.equals(providerB));
        Model a = testModel(id, providerA);
        Model b = testModel(id, providerB);
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isFalse();
    }

    /**
     * 属性：任一参数为 null 时，modelsAreEqual 返回 false。
     */
    @Property(tries = 200)
    void modelsAreEqual_nullArgument_returnsFalse(
            @ForAll("modelIds") String id,
            @ForAll("providerNames") String provider
    ) {
        Model model = testModel(id, provider);
        assertThat(ModelRegistry.modelsAreEqual(null, model)).isFalse();
        assertThat(ModelRegistry.modelsAreEqual(model, null)).isFalse();
        assertThat(ModelRegistry.modelsAreEqual(null, null)).isFalse();
    }

    /**
     * 属性：modelsAreEqual 是对称的。
     */
    @Property(tries = 300)
    void modelsAreEqual_isSymmetric(
            @ForAll("modelIds") String idA,
            @ForAll("modelIds") String idB,
            @ForAll("providerNames") String providerA,
            @ForAll("providerNames") String providerB
    ) {
        Model a = testModel(idA, providerA);
        Model b = testModel(idB, providerB);
        assertThat(ModelRegistry.modelsAreEqual(a, b))
                .isEqualTo(ModelRegistry.modelsAreEqual(b, a));
    }

    // ==================== 辅助方法 ====================

    private static Model testModel(String id, String provider) {
        return new Model(
                id, "Test Model", "test-api", provider,
                "https://api.test.com", false, List.of("text"),
                DEFAULT_COST, 128000, 4096, null, null
        );
    }
}
