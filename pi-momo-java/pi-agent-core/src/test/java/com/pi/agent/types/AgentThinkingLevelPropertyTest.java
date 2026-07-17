package com.pi.agent.types;

import com.pi.ai.core.types.ThinkingLevel;
import net.jqwik.api.*;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：ThinkingLevel 非 OFF 映射。
 *
 * <p><b>Validates: Requirements 1.3</b>
 */
class AgentThinkingLevelPropertyTest {

    /**
     * 生成所有非 OFF 的 AgentThinkingLevel 值。
     */
    @Provide
    Arbitrary<AgentThinkingLevel> nonOffLevels() {
        return Arbitraries.of(
                Arrays.stream(AgentThinkingLevel.values())
                        .filter(level -> level != AgentThinkingLevel.OFF)
                        .toArray(AgentThinkingLevel[]::new)
        );
    }

    /**
     * Property 15: 所有非 OFF 的 AgentThinkingLevel 值调用 toPiAiThinkingLevel()
     * 返回名称相同的 pi-ai-java ThinkingLevel。
     *
     * <p><b>Validates: Requirements 1.3</b>
     */
    @Property(tries = 100)
    void nonOff_mapsToSameNamedThinkingLevel(@ForAll("nonOffLevels") AgentThinkingLevel agentLevel) {
        ThinkingLevel result = agentLevel.toPiAiThinkingLevel();

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo(agentLevel.name());
    }

    /**
     * OFF 映射为 null。
     *
     * <p><b>Validates: Requirements 1.3</b>
     */
    @Property(tries = 1)
    void off_mapsToNull() {
        ThinkingLevel result = AgentThinkingLevel.OFF.toPiAiThinkingLevel();

        assertThat(result).isNull();
    }
}
