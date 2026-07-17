package com.pi.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 2: ProxyAssistantMessageEvent 序列化 round-trip
 *
 * <p>为所有 12 种 ProxyAssistantMessageEvent record 子类型生成随机实例，
 * 验证 Jackson serialize → deserialize 产生等价对象。
 *
 * <p><b>Validates: Requirements 40.2, 40.5</b>
 */
class ProxyEventSerializationPropertyTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== Primitive generators ====================

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .alpha()
                .numeric();
    }

    @Provide
    Arbitrary<Integer> contentIndices() {
        return Arbitraries.integers().between(0, 10);
    }

    @Provide
    Arbitrary<StopReason> stopReasons() {
        return Arbitraries.of(StopReason.STOP, StopReason.LENGTH, StopReason.TOOL_USE,
                StopReason.ERROR, StopReason.ABORTED);
    }

    @Provide
    Arbitrary<Usage.Cost> costs() {
        return Combinators.combine(
                Arbitraries.doubles().between(0.0, 10.0),
                Arbitraries.doubles().between(0.0, 10.0),
                Arbitraries.doubles().between(0.0, 10.0),
                Arbitraries.doubles().between(0.0, 10.0),
                Arbitraries.doubles().between(0.0, 50.0)
        ).as(Usage.Cost::new);
    }

    @Provide
    Arbitrary<Usage> usages() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 1000),
                Arbitraries.integers().between(0, 1000),
                Arbitraries.integers().between(0, 500),
                Arbitraries.integers().between(0, 500),
                Arbitraries.integers().between(0, 3000),
                costs().injectNull(0.3)
        ).as(Usage::new);
    }

    // ==================== ProxyAssistantMessageEvent generator ====================

    @Provide
    Arbitrary<ProxyAssistantMessageEvent> allProxyEvents() {
        return Arbitraries.oneOf(
                // 1. Start
                Arbitraries.just(new ProxyAssistantMessageEvent.Start())
                        .map(e -> e),
                // 2. TextStart
                contentIndices()
                        .map(idx -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.TextStart(idx)),
                // 3. TextDelta
                Combinators.combine(contentIndices(), safeStrings())
                        .as((idx, delta) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.TextDelta(idx, delta)),
                // 4. TextEnd
                Combinators.combine(contentIndices(), safeStrings().injectNull(0.5))
                        .as((idx, sig) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.TextEnd(idx, sig)),
                // 5. ThinkingStart
                contentIndices()
                        .map(idx -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ThinkingStart(idx)),
                // 6. ThinkingDelta
                Combinators.combine(contentIndices(), safeStrings())
                        .as((idx, delta) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ThinkingDelta(idx, delta)),
                // 7. ThinkingEnd
                Combinators.combine(contentIndices(), safeStrings().injectNull(0.5))
                        .as((idx, sig) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ThinkingEnd(idx, sig)),
                // 8. ToolCallStart
                Combinators.combine(contentIndices(), safeStrings(), safeStrings())
                        .as((idx, id, name) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ToolCallStart(idx, id, name)),
                // 9. ToolCallDelta
                Combinators.combine(contentIndices(), safeStrings())
                        .as((idx, delta) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ToolCallDelta(idx, delta)),
                // 10. ToolCallEnd
                contentIndices()
                        .map(idx -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.ToolCallEnd(idx)),
                // 11. Done
                Combinators.combine(stopReasons(), usages())
                        .as((reason, usage) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.Done(reason, usage)),
                // 12. Error
                Combinators.combine(stopReasons(), safeStrings().injectNull(0.5), usages())
                        .as((reason, errMsg, usage) -> (ProxyAssistantMessageEvent) new ProxyAssistantMessageEvent.Error(reason, errMsg, usage))
        );
    }

    // ==================== Property test ====================

    /**
     * Property 2: ProxyAssistantMessageEvent 序列化 round-trip.
     *
     * <p>For all 12 ProxyAssistantMessageEvent subtypes, serialize to JSON and
     * deserialize back, verifying the result is equivalent to the original.
     *
     * <p><b>Validates: Requirements 40.2, 40.5</b>
     */
    @Property(tries = 200)
    void proxyAssistantMessageEvent_roundTrip(
            @ForAll("allProxyEvents") ProxyAssistantMessageEvent original
    ) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ProxyAssistantMessageEvent deserialized = MAPPER.readValue(json, ProxyAssistantMessageEvent.class);

        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.getClass()).isEqualTo(original.getClass());
        assertThat(deserialized).isEqualTo(original);
    }
}
