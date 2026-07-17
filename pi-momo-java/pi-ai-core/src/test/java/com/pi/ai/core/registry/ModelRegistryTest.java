package com.pi.ai.core.registry;

import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ModelCost;
import com.pi.ai.core.types.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ModelRegistry 单元测试。
 */
class ModelRegistryTest {

    // ---- getModel ----

    @Test
    void getModel_returnsCorrectModel() {
        Model model = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(model).isNotNull();
        assertThat(model.id()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(model.name()).isEqualTo("Claude Sonnet 4");
        assertThat(model.api()).isEqualTo("anthropic-messages");
        assertThat(model.provider()).isEqualTo("anthropic");
        assertThat(model.reasoning()).isTrue();
    }

    @Test
    void getModel_returnsNullForUnknownProvider() {
        assertThat(ModelRegistry.getModel("nonexistent", "some-model")).isNull();
    }

    @Test
    void getModel_returnsNullForUnknownModelId() {
        assertThat(ModelRegistry.getModel("anthropic", "nonexistent-model")).isNull();
    }

    // ---- getProviders ----

    @Test
    void getProviders_returnsAllProviders() {
        List<String> providers = ModelRegistry.getProviders();
        assertThat(providers).containsExactlyInAnyOrder(
                "anthropic", "openai", "google", "mistral", "amazon-bedrock"
        );
    }

    // ---- getModels ----

    @Test
    void getModels_returnsAllModelsForProvider() {
        List<Model> models = ModelRegistry.getModels("anthropic");
        assertThat(models).hasSize(3);
        assertThat(models).extracting(Model::id).containsExactlyInAnyOrder(
                "claude-3-5-haiku-20241022",
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514"
        );
    }

    @Test
    void getModels_returnsEmptyListForUnknownProvider() {
        assertThat(ModelRegistry.getModels("nonexistent")).isEmpty();
    }

    // ---- calculateCost ----

    @Test
    void calculateCost_formulaCorrectness() {
        // 使用 anthropic claude-sonnet-4: input=3, output=15, cacheRead=0.3, cacheWrite=3.75
        Model model = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(model).isNotNull();

        Usage usage = new Usage(1000, 500, 200, 100, 1800,
                new Usage.Cost(0, 0, 0, 0, 0));

        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);

        // input: (3 / 1000000) * 1000 = 0.003
        assertThat(cost.input()).isCloseTo(0.003, within(1e-10));
        // output: (15 / 1000000) * 500 = 0.0075
        assertThat(cost.output()).isCloseTo(0.0075, within(1e-10));
        // cacheRead: (0.3 / 1000000) * 200 = 0.00006
        assertThat(cost.cacheRead()).isCloseTo(0.00006, within(1e-10));
        // cacheWrite: (3.75 / 1000000) * 100 = 0.000375
        assertThat(cost.cacheWrite()).isCloseTo(0.000375, within(1e-10));
        // total = 0.003 + 0.0075 + 0.00006 + 0.000375 = 0.010935
        assertThat(cost.total()).isCloseTo(0.010935, within(1e-10));
    }

    @Test
    void calculateCost_zeroCostForZeroUsage() {
        Model model = ModelRegistry.getModel("openai", "gpt-4o");
        assertThat(model).isNotNull();

        Usage usage = new Usage(0, 0, 0, 0, 0,
                new Usage.Cost(0, 0, 0, 0, 0));

        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);
        assertThat(cost.total()).isEqualTo(0.0);
    }

    // ---- supportsXhigh ----

    @Test
    void supportsXhigh_returnsFalseForRegularModels() {
        Model model = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(model).isNotNull();
        assertThat(ModelRegistry.supportsXhigh(model)).isFalse();
    }

    @Test
    void supportsXhigh_detectsGpt52() {
        // 构造一个包含 gpt-5.2 的模型
        Model model = new Model("gpt-5.2-preview", "GPT-5.2", "openai-responses",
                "openai", "https://api.openai.com/v1", true,
                List.of("text"), new ModelCost(1, 2, 0, 0),
                128000, 16384, null, null);
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    @Test
    void supportsXhigh_detectsGpt53() {
        Model model = new Model("gpt-5.3", "GPT-5.3", "openai-responses",
                "openai", "https://api.openai.com/v1", true,
                List.of("text"), new ModelCost(1, 2, 0, 0),
                128000, 16384, null, null);
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    @Test
    void supportsXhigh_detectsGpt54() {
        Model model = new Model("gpt-5.4-turbo", "GPT-5.4", "openai-responses",
                "openai", "https://api.openai.com/v1", true,
                List.of("text"), new ModelCost(1, 2, 0, 0),
                128000, 16384, null, null);
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    @Test
    void supportsXhigh_detectsOpus46WithDash() {
        Model model = new Model("claude-opus-4-6-20250801", "Opus 4.6", "anthropic-messages",
                "anthropic", "https://api.anthropic.com", true,
                List.of("text"), new ModelCost(5, 25, 0.5, 6.25),
                200000, 128000, null, null);
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    @Test
    void supportsXhigh_detectsOpus46WithDot() {
        Model model = new Model("claude-opus-4.6", "Opus 4.6", "anthropic-messages",
                "anthropic", "https://api.anthropic.com", true,
                List.of("text"), new ModelCost(5, 25, 0.5, 6.25),
                200000, 128000, null, null);
        assertThat(ModelRegistry.supportsXhigh(model)).isTrue();
    }

    // ---- modelsAreEqual ----

    @Test
    void modelsAreEqual_sameIdAndProvider() {
        Model a = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        Model b = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isTrue();
    }

    @Test
    void modelsAreEqual_differentId() {
        Model a = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        Model b = ModelRegistry.getModel("anthropic", "claude-3-5-haiku-20241022");
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isFalse();
    }

    @Test
    void modelsAreEqual_differentProvider() {
        // 构造两个 id 相同但 provider 不同的模型
        Model a = new Model("test-model", "Test", "api-a", "provider-a",
                "http://a", false, List.of("text"), new ModelCost(0, 0, 0, 0),
                128000, 4096, null, null);
        Model b = new Model("test-model", "Test", "api-b", "provider-b",
                "http://b", false, List.of("text"), new ModelCost(0, 0, 0, 0),
                128000, 4096, null, null);
        assertThat(ModelRegistry.modelsAreEqual(a, b)).isFalse();
    }

    @Test
    void modelsAreEqual_returnsFalseForNullA() {
        Model b = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(ModelRegistry.modelsAreEqual(null, b)).isFalse();
    }

    @Test
    void modelsAreEqual_returnsFalseForNullB() {
        Model a = ModelRegistry.getModel("anthropic", "claude-sonnet-4-20250514");
        assertThat(ModelRegistry.modelsAreEqual(a, null)).isFalse();
    }

    @Test
    void modelsAreEqual_returnsFalseForBothNull() {
        assertThat(ModelRegistry.modelsAreEqual(null, null)).isFalse();
    }

    // ---- models.json 加载验证 ----

    @Test
    void modelsJson_loadsAllProviders() {
        // 验证 5 个 provider 都已加载
        assertThat(ModelRegistry.getProviders()).hasSize(5);
    }

    @Test
    void modelsJson_modelFieldsAreComplete() {
        Model model = ModelRegistry.getModel("openai", "gpt-4o");
        assertThat(model).isNotNull();
        assertThat(model.id()).isEqualTo("gpt-4o");
        assertThat(model.name()).isEqualTo("GPT-4o");
        assertThat(model.api()).isEqualTo("openai-responses");
        assertThat(model.provider()).isEqualTo("openai");
        assertThat(model.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(model.reasoning()).isFalse();
        assertThat(model.input()).containsExactly("text", "image");
        assertThat(model.cost()).isNotNull();
        assertThat(model.cost().input()).isEqualTo(2.5);
        assertThat(model.contextWindow()).isEqualTo(128000);
        assertThat(model.maxTokens()).isEqualTo(16384);
    }
}
