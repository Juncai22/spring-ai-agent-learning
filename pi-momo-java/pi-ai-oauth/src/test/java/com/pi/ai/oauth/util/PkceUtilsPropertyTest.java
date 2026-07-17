package com.pi.ai.oauth.util;

import net.jqwik.api.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 26: PKCE verifier/challenge round-trip
 */
class PkceUtilsPropertyTest {

    @Property(tries = 100)
    void verifierIsBase64UrlEncoded() {
        PkceUtils.PkceResult result = PkceUtils.generatePKCE();
        // Base64URL characters: A-Z, a-z, 0-9, -, _
        assertThat(result.verifier()).matches("[A-Za-z0-9_-]+");
    }

    @Property(tries = 100)
    void challengeIsBase64UrlEncoded() {
        PkceUtils.PkceResult result = PkceUtils.generatePKCE();
        assertThat(result.challenge()).matches("[A-Za-z0-9_-]+");
    }

    @Property(tries = 100)
    void challengeIsSha256OfVerifier() throws Exception {
        PkceUtils.PkceResult result = PkceUtils.generatePKCE();

        // Recompute: SHA-256(verifier) → Base64URL
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(result.verifier().getBytes(StandardCharsets.UTF_8));
        String expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        assertThat(result.challenge()).isEqualTo(expectedChallenge);
    }

    @Property(tries = 50)
    void verifierHasExpectedLength() {
        PkceUtils.PkceResult result = PkceUtils.generatePKCE();
        // 32 bytes → Base64URL without padding = 43 characters
        assertThat(result.verifier()).hasSize(43);
    }

    @Property(tries = 50)
    void challengeHasExpectedLength() {
        PkceUtils.PkceResult result = PkceUtils.generatePKCE();
        // SHA-256 = 32 bytes → Base64URL without padding = 43 characters
        assertThat(result.challenge()).hasSize(43);
    }

    @Property(tries = 30)
    void eachCallProducesUniqueVerifier() {
        Set<String> verifiers = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            verifiers.add(PkceUtils.generatePKCE().verifier());
        }
        // With 32 bytes of randomness, collisions are astronomically unlikely
        assertThat(verifiers).hasSize(10);
    }
}
