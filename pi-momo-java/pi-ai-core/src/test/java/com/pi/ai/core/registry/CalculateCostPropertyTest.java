package com.pi.ai.core.registry;

import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ModelCost;
import com.pi.ai.core.types.Usage;

import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 属性测试：calculateCost 数学正确性。
 *
 * <p><b>Property 7</b>: 验证费用计算公式 {@code (price / 1000000) * tokens} 的数学正确性。
 *
 * <p><b>Validates: Requirements 8.5</b>
 */
class CalculateCostPropertyTest {

    private static final double TOLERANCE = 1e-10;

    // ==================== Arbitrary 生成器 ====================

    @Provide
    Arbitrary<ModelCost> modelCosts() {
        return Combinators.combine(
                Arbitraries.doubles().between(0.0, 100.0),   // input price
                Arbitraries.doubles().between(0.0, 100.0),   // output price
                Arbitraries.doubles().between(0.0, 100.0),   // cacheRead price
                Arbitraries.doubles().between(0.0, 100.0)    // cacheWrite price
        ).as(ModelCost::new);
    }

    @Provide
    Arbitrary<Usage> usages() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 1_000_000),  // input tokens
                Arbitraries.integers().between(0, 1_000_000),  // output tokens
                Arbitraries.integers().between(0, 1_000_000),  // cacheRead tokens
                Arbitraries.integers().between(0, 1_000_000),  // cacheWrite tokens
                Arbitraries.integers().between(0, 4_000_000)   // totalTokens
        ).as((input, output, cacheRead, cacheWrite, total) ->
                new Usage(input, output, cacheRead, cacheWrite, total, null)
        );
    }

    // ==================== 属性测试 ====================

    /**
     * 属性：calculateCost 各分项公式正确。
     *
     * <p>cost.input = (model.cost.input / 1000000) * usage.input
     * <p>cost.output = (model.cost.output / 1000000) * usage.output
     * <p>cost.cacheRead = (model.cost.cacheRead / 1000000) * usage.cacheRead
     * <p>cost.cacheWrite = (model.cost.cacheWrite / 1000000) * usage.cacheWrite
     */
    @Property(tries = 1000)
    void calculateCost_formulaIsCorrect(
            @ForAll("modelCosts") ModelCost modelCost,
            @ForAll("usages") Usage usage
    ) {
        Model model = testModel(modelCost);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);

        double expectedInput = (modelCost.input() / 1_000_000.0) * usage.input();
        double expectedOutput = (modelCost.output() / 1_000_000.0) * usage.output();
        double expectedCacheRead = (modelCost.cacheRead() / 1_000_000.0) * usage.cacheRead();
        double expectedCacheWrite = (modelCost.cacheWrite() / 1_000_000.0) * usage.cacheWrite();

        assertThat(cost.input()).isCloseTo(expectedInput, within(TOLERANCE));
        assertThat(cost.output()).isCloseTo(expectedOutput, within(TOLERANCE));
        assertThat(cost.cacheRead()).isCloseTo(expectedCacheRead, within(TOLERANCE));
        assertThat(cost.cacheWrite()).isCloseTo(expectedCacheWrite, within(TOLERANCE));
    }

    /**
     * 属性：calculateCost 的 total 等于四个分项之和。
     */
    @Property(tries = 1000)
    void calculateCost_totalIsSumOfParts(
            @ForAll("modelCosts") ModelCost modelCost,
            @ForAll("usages") Usage usage
    ) {
        Model model = testModel(modelCost);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);

        double expectedTotal = cost.input() + cost.output() + cost.cacheRead() + cost.cacheWrite();
        assertThat(cost.total()).isCloseTo(expectedTotal, within(TOLERANCE));
    }

    /**
     * 属性：零 token 用量时费用为零。
     */
    @Property(tries = 200)
    void calculateCost_zeroUsage_zeroCost(@ForAll("modelCosts") ModelCost modelCost) {
        Model model = testModel(modelCost);
        Usage zeroUsage = new Usage(0, 0, 0, 0, 0, null);

        Usage.Cost cost = ModelRegistry.calculateCost(model, zeroUsage);

        assertThat(cost.input()).isEqualTo(0.0);
        assertThat(cost.output()).isEqualTo(0.0);
        assertThat(cost.cacheRead()).isEqualTo(0.0);
        assertThat(cost.cacheWrite()).isEqualTo(0.0);
        assertThat(cost.total()).isEqualTo(0.0);
    }

    /**
     * 属性：零定价时费用为零。
     */
    @Property(tries = 200)
    void calculateCost_zeroPricing_zeroCost(@ForAll("usages") Usage usage) {
        Model model = testModel(new ModelCost(0.0, 0.0, 0.0, 0.0));

        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);

        assertThat(cost.input()).isEqualTo(0.0);
        assertThat(cost.output()).isEqualTo(0.0);
        assertThat(cost.cacheRead()).isEqualTo(0.0);
        assertThat(cost.cacheWrite()).isEqualTo(0.0);
        assertThat(cost.total()).isEqualTo(0.0);
    }

    /**
     * 属性：费用始终非负（价格和 token 数均非负时）。
     */
    @Property(tries = 500)
    void calculateCost_alwaysNonNegative(
            @ForAll("modelCosts") ModelCost modelCost,
            @ForAll("usages") Usage usage
    ) {
        Model model = testModel(modelCost);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);

        assertThat(cost.input()).isGreaterThanOrEqualTo(0.0);
        assertThat(cost.output()).isGreaterThanOrEqualTo(0.0);
        assertThat(cost.cacheRead()).isGreaterThanOrEqualTo(0.0);
        assertThat(cost.cacheWrite()).isGreaterThanOrEqualTo(0.0);
        assertThat(cost.total()).isGreaterThanOrEqualTo(0.0);
    }

    // ==================== 辅助方法 ====================

    private static Model testModel(ModelCost cost) {
        return new Model(
                "test-model", "Test Model", "test-api", "test-provider",
                "https://api.test.com", false, List.of("text"),
                cost, 128000, 4096, null, null
        );
    }
}
