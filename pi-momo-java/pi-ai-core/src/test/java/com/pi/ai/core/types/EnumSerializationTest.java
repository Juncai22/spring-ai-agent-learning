package com.pi.ai.core.types;

import com.pi.ai.core.util.PiAiJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for enum Jackson serialization and deserialization.
 */
class EnumSerializationTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // --- StopReason ---

    @Test
    void stopReason_serializesToExpectedJsonValues() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(StopReason.STOP)).isEqualTo("\"stop\"");
        assertThat(MAPPER.writeValueAsString(StopReason.LENGTH)).isEqualTo("\"length\"");
        assertThat(MAPPER.writeValueAsString(StopReason.TOOL_USE)).isEqualTo("\"toolUse\"");
        assertThat(MAPPER.writeValueAsString(StopReason.ERROR)).isEqualTo("\"error\"");
        assertThat(MAPPER.writeValueAsString(StopReason.ABORTED)).isEqualTo("\"aborted\"");
    }

    @Test
    void stopReason_deserializesFromJsonValues() throws JsonProcessingException {
        assertThat(MAPPER.readValue("\"stop\"", StopReason.class)).isEqualTo(StopReason.STOP);
        assertThat(MAPPER.readValue("\"length\"", StopReason.class)).isEqualTo(StopReason.LENGTH);
        assertThat(MAPPER.readValue("\"toolUse\"", StopReason.class)).isEqualTo(StopReason.TOOL_USE);
        assertThat(MAPPER.readValue("\"error\"", StopReason.class)).isEqualTo(StopReason.ERROR);
        assertThat(MAPPER.readValue("\"aborted\"", StopReason.class)).isEqualTo(StopReason.ABORTED);
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void stopReason_roundTrip(StopReason value) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(value);
        StopReason deserialized = MAPPER.readValue(json, StopReason.class);
        assertThat(deserialized).isEqualTo(value);
    }

    @Test
    void stopReason_unknownValueDeserializesToNull() throws JsonProcessingException {
        assertThat(MAPPER.readValue("\"unknown_reason\"", StopReason.class)).isNull();
    }

    // --- ThinkingLevel ---

    @Test
    void thinkingLevel_serializesToLowercaseValues() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(ThinkingLevel.MINIMAL)).isEqualTo("\"minimal\"");
        assertThat(MAPPER.writeValueAsString(ThinkingLevel.LOW)).isEqualTo("\"low\"");
        assertThat(MAPPER.writeValueAsString(ThinkingLevel.MEDIUM)).isEqualTo("\"medium\"");
        assertThat(MAPPER.writeValueAsString(ThinkingLevel.HIGH)).isEqualTo("\"high\"");
        assertThat(MAPPER.writeValueAsString(ThinkingLevel.XHIGH)).isEqualTo("\"xhigh\"");
    }

    @ParameterizedTest
    @EnumSource(ThinkingLevel.class)
    void thinkingLevel_roundTrip(ThinkingLevel value) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(value);
        ThinkingLevel deserialized = MAPPER.readValue(json, ThinkingLevel.class);
        assertThat(deserialized).isEqualTo(value);
    }

    // --- CacheRetention ---

    @Test
    void cacheRetention_serializesToLowercaseValues() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(CacheRetention.NONE)).isEqualTo("\"none\"");
        assertThat(MAPPER.writeValueAsString(CacheRetention.SHORT)).isEqualTo("\"short\"");
        assertThat(MAPPER.writeValueAsString(CacheRetention.LONG)).isEqualTo("\"long\"");
    }

    @ParameterizedTest
    @EnumSource(CacheRetention.class)
    void cacheRetention_roundTrip(CacheRetention value) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(value);
        CacheRetention deserialized = MAPPER.readValue(json, CacheRetention.class);
        assertThat(deserialized).isEqualTo(value);
    }

    // --- Transport ---

    @Test
    void transport_serializesToLowercaseValues() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(Transport.SSE)).isEqualTo("\"sse\"");
        assertThat(MAPPER.writeValueAsString(Transport.WEBSOCKET)).isEqualTo("\"websocket\"");
        assertThat(MAPPER.writeValueAsString(Transport.AUTO)).isEqualTo("\"auto\"");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void transport_roundTrip(Transport value) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(value);
        Transport deserialized = MAPPER.readValue(json, Transport.class);
        assertThat(deserialized).isEqualTo(value);
    }

    // --- Unknown values gracefully handled ---

    @Test
    void unknownEnumValues_deserializeToNull() throws JsonProcessingException {
        assertThat(MAPPER.readValue("\"unknown\"", ThinkingLevel.class)).isNull();
        assertThat(MAPPER.readValue("\"unknown\"", CacheRetention.class)).isNull();
        assertThat(MAPPER.readValue("\"unknown\"", Transport.class)).isNull();
    }
}
