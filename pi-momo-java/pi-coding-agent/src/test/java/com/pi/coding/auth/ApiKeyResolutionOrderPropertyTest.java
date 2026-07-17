package com.pi.coding.auth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: API Key Resolution Order
 * 
 * Validates: Requirement 14.8
 * getApiKey resolves in order: runtime override → stored credential → OAuth → env var → fallback
 */
class ApiKeyResolutionOrderPropertyTest {
    
    @Property(tries = 100)
    void runtimeOverrideTakesPrecedence(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String runtimeKey,
        @ForAll @StringLength(min = 1, max = 50) String storedKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set stored credential
        storage.setCredential(provider, new ApiKeyCredential(storedKey));
        
        // Set runtime override
        storage.setRuntimeApiKey(provider, runtimeKey);
        
        // Runtime override should win
        String result = storage.getApiKey(provider);
        assertThat(result).isEqualTo(runtimeKey);
    }
    
    @Property(tries = 100)
    void storedApiKeyUsedWhenNoRuntimeOverride(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String storedKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set stored credential only
        storage.setCredential(provider, new ApiKeyCredential(storedKey));
        
        // Stored key should be returned
        String result = storage.getApiKey(provider);
        assertThat(result).isEqualTo(storedKey);
    }
    
    @Property(tries = 100)
    void oauthTokenUsedWhenNotExpired(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String accessToken,
        @ForAll @StringLength(min = 1, max = 50) String refreshToken
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set OAuth credential with future expiration
        long futureExpiry = System.currentTimeMillis() + 3600_000; // 1 hour from now
        storage.setCredential(provider, new OAuthCredential(accessToken, refreshToken, futureExpiry, null));
        
        // OAuth access token should be returned
        String result = storage.getApiKey(provider);
        assertThat(result).isEqualTo(accessToken);
    }
    
    @Property(tries = 100)
    void expiredOauthTokenNotUsed(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String accessToken,
        @ForAll @StringLength(min = 1, max = 50) String refreshToken,
        @ForAll @StringLength(min = 1, max = 50) String fallbackKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set OAuth credential with past expiration
        long pastExpiry = System.currentTimeMillis() - 1000; // 1 second ago
        storage.setCredential(provider, new OAuthCredential(accessToken, refreshToken, pastExpiry, null));
        
        // Expired OAuth should not be used, fallback should be called
        String result = storage.getApiKey(provider, p -> fallbackKey);
        assertThat(result).isEqualTo(fallbackKey);
    }
    
    @Property(tries = 100)
    void fallbackUsedWhenNoOtherSource(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String fallbackKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // No credentials set, fallback should be used
        String result = storage.getApiKey(provider, p -> fallbackKey);
        assertThat(result).isEqualTo(fallbackKey);
    }
    
    @Property(tries = 100)
    void nullReturnedWhenNoSourceAvailable(
        @ForAll @StringLength(min = 1, max = 50) String provider
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // No credentials, no fallback
        String result = storage.getApiKey(provider);
        assertThat(result).isNull();
    }
    
    @Property(tries = 50)
    void clearingRuntimeOverrideFallsBackToStored(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String runtimeKey,
        @ForAll @StringLength(min = 1, max = 50) String storedKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set both
        storage.setCredential(provider, new ApiKeyCredential(storedKey));
        storage.setRuntimeApiKey(provider, runtimeKey);
        
        // Verify runtime wins
        assertThat(storage.getApiKey(provider)).isEqualTo(runtimeKey);
        
        // Clear runtime
        storage.clearRuntimeApiKey(provider);
        
        // Now stored should be returned
        assertThat(storage.getApiKey(provider)).isEqualTo(storedKey);
    }
    
    @Property(tries = 50)
    void removingCredentialFallsBackToFallback(
        @ForAll @StringLength(min = 1, max = 50) String provider,
        @ForAll @StringLength(min = 1, max = 50) String storedKey,
        @ForAll @StringLength(min = 1, max = 50) String fallbackKey
    ) {
        AuthStorage storage = AuthStorage.inMemory();
        
        // Set stored credential
        storage.setCredential(provider, new ApiKeyCredential(storedKey));
        
        // Verify stored is used
        assertThat(storage.getApiKey(provider)).isEqualTo(storedKey);
        
        // Remove credential
        storage.removeCredential(provider);
        
        // Now fallback should be used
        assertThat(storage.getApiKey(provider, p -> fallbackKey)).isEqualTo(fallbackKey);
    }
}
