package com.pi.agent.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.types.ImageContent;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.UserContentBlock;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：AgentToolResult 序列化 round-trip。
 *
 * <p><b>Property 3: AgentToolResult 序列化 round-trip</b>
 * <p>生成随机 AgentToolResult 实例，验证 Jackson 序列化后反序列化产生等价对象。
 *
 * <p><b>Validates: Requirements 40.4, 40.5</b>
 */
class AgentToolResultPropertyTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;
    private static final TypeReference<AgentToolResult<String>> TYPE_REF =
            new TypeReference<>() {};

    // ==================== Arbitrary 生成器 ====================

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(100)
                .alpha()
                .numeric();
    }

    @Provide
    Arbitrary<String> base64Data() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=");
    }

    @Provide
    Arbitrary<String> mimeTypes() {
        return Arbitraries.of("image/png", "image/jpeg", "image/gif", "image/webp");
    }

    @Provide
    Arbitrary<TextContent> textContents() {
        return Combinators.combine(
                safeStrings(),
                safeStrings().injectNull(0.5)
        ).as((text, sig) -> new TextContent("text", text, sig));
    }

    @Provide
    Arbitrary<ImageContent> imageContents() {
        return Combinators.combine(
                base64Data(),
                mimeTypes()
        ).as((data, mime) -> new ImageContent("image", data, mime));
    }

    @Provide
    Arbitrary<UserContentBlock> userContentBlocks() {
        return Arbitraries.oneOf(
                textContents().map(t -> t),
                imageContents().map(t -> t)
        );
    }

    @Provide
    Arbitrary<AgentToolResult<String>> agentToolResults() {
        return Combinators.combine(
                userContentBlocks().list().ofMinSize(0).ofMaxSize(5),
                safeStrings().injectNull(0.3)
        ).as(AgentToolResult::new);
    }

    // ==================== 属性测试 ====================

    /**
     * Property 3: AgentToolResult&lt;String&gt; 序列化→反序列化 round-trip。
     *
     * <p><b>Validates: Requirements 40.4, 40.5</b>
     */
    @Property(tries = 200)
    void agentToolResult_roundTrip(
            @ForAll("agentToolResults") AgentToolResult<String> original
    ) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        AgentToolResult<String> deserialized = MAPPER.readValue(json, TYPE_REF);

        assertThat(deserialized.details()).isEqualTo(original.details());
        assertThat(deserialized.content()).isEqualTo(original.content());
    }
}
