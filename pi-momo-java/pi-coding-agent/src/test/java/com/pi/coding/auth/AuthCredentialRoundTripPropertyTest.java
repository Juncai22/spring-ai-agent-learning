package com.pi.coding.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Auth Credential Round-Trip
 * 
 * Validates: Requirement 14.1, 14.2, 14.3
 * All AuthCredential subtypes serialize to JSON and deserialize back to equivalent objects.
 */
class AuthCredentialRoundTripPropertyTest {
    
    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;
    
    @Property(tries = 100)
    void apiKeyCredentialRoundTrip(
        @ForAll @StringLength(min = 1, max = 100) String apiKey
    ) throws JsonProcessingException {
        ApiKeyCredential original = new ApiKeyCredential(apiKey);
        
        String json = MAPPER.writeValueAsString(original);
        AuthCredential deserialized = MAPPER.readValue(json, AuthCredential.class);
        
        assertThat(deserialized).isInstanceOf(ApiKeyCredential.class);
        ApiKeyCredential result = (ApiKeyCredential) deserialized;
        assertThat(result.apiKey()).isEqualTo(original.apiKey());
        assertThat(result.type()).isEqualTo("apiKey");
    }
    
    @Property(tries = 100)
    void oauthCredentialRoundTrip(
        @ForAll @StringLength(min = 1, max = 100) String accessToken,
        @ForAll @StringLength(min = 1, max = 100) String refreshToken,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 1) long expiresAt
    ) throws JsonProcessingException {
        OAuthCredential original = new OAuthCredential(accessToken, refreshToken, expiresAt, null);
        
        String json = MAPPER.writeValueAsString(original);
        AuthCredential deserialized = MAPPER.readValue(json, AuthCredential.class);
        
        assertThat(deserialized).isInstanceOf(OAuthCredential.class);
        OAuthCredential result = (OAuthCredential) deserialized;
        assertThat(result.accessToken()).isEqualTo(original.accessToken());
        assertThat(result.refreshToken()).isEqualTo(original.refreshToken());
        assertThat(result.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(result.extra()).isNull();
        assertThat(result.type()).isEqualTo("oauth");
    }
    
    @Property(tries = 50)
    void oauthCredentialWithExtraRoundTrip(
        @ForAll @StringLength(min = 1, max = 50) String accessToken,
        @ForAll @StringLength(min = 1, max = 50) String refreshToken,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 1) long expiresAt,
        @ForAll @StringLength(min = 1, max = 20) String extraKey,
        @ForAll @StringLength(min = 0, max = 50) String extraValue
    ) throws JsonProcessingException {
        Map<String, Object> extra = new HashMap<>();
        extra.put(extraKey, extraValue);
        
        OAuthCredential original = new OAuthCredential(accessToken, refreshToken, expiresAt, extra);
        
        String json = MAPPER.writeValueAsString(original);
        AuthCredential deserialized = MAPPER.readValue(json, AuthCredential.class);
        
        assertThat(deserialized).isInstanceOf(OAuthCredential.class);
        OAuthCredential result = (OAuthCredential) deserialized;
        assertThat(result.accessToken()).isEqualTo(original.accessToken());
        assertThat(result.refreshToken()).isEqualTo(original.refreshToken());
        assertThat(result.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(result.extra()).isNotNull();
        assertThat(result.extra().get(extraKey)).isEqualTo(extraValue);
    }
    
    @Property(tries = 100)
    void credentialTypeFieldIsPreserved(@ForAll("anyCredential") AuthCredential credential) 
        throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(credential);
        
        // Verify type field is in JSON
        assertThat(json).contains("\"type\"");
        
        AuthCredential deserialized = MAPPER.readValue(json, AuthCredential.class);
        assertThat(deserialized.type()).isEqualTo(credential.type());
    }
    
    @Provide
    Arbitrary<AuthCredential> anyCredential() {
        Arbitrary<ApiKeyCredential> apiKey = Arbitraries.strings()
            .ofMinLength(1).ofMaxLength(50)
            .map(ApiKeyCredential::new);
        
        Arbitrary<OAuthCredential> oauth = Combinators.combine(
            Arbitraries.strings().ofMinLength(1).ofMaxLength(50),
            Arbitraries.strings().ofMinLength(1).ofMaxLength(50),
            Arbitraries.longs().between(0, Long.MAX_VALUE - 1)
        ).as((access, refresh, expires) -> new OAuthCredential(access, refresh, expires, null));
        
        return Arbitraries.oneOf(apiKey, oauth);
    }
}
