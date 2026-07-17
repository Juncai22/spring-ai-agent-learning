package com.pi.ai.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PiAiJsonTest {

    @Test
    void mapperShouldNotBeNull() {
        assertThat(PiAiJson.MAPPER).isNotNull();
        assertThat(PiAiJson.MAPPER).isInstanceOf(ObjectMapper.class);
    }

    @Test
    void mapperShouldIgnoreUnknownProperties() {
        assertThat(PiAiJson.MAPPER.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();
    }

    @Test
    void mapperShouldReadUnknownEnumValuesAsNull() {
        assertThat(PiAiJson.MAPPER.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL))
                .isTrue();
    }

    @Test
    void mapperShouldExcludeNullFieldsFromSerialization() {
        assertThat(PiAiJson.MAPPER.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion())
                .isEqualTo(JsonInclude.Include.NON_NULL);
    }

    @Test
    void mapperShouldDeserializeJsonWithUnknownFields() {
        String json = "{\"name\":\"test\",\"unknownField\":123}";
        assertThatNoException().isThrownBy(() -> PiAiJson.MAPPER.readTree(json));
    }

    @Test
    void mapperShouldSerializeObjectOmittingNulls() throws Exception {
        record Sample(String name, String value) {}
        String json = PiAiJson.MAPPER.writeValueAsString(new Sample("hello", null));
        assertThat(json).contains("\"name\"").doesNotContain("\"value\"");
    }
}
