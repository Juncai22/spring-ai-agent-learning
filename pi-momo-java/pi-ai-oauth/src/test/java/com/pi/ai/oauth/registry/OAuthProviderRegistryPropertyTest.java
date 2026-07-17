package com.pi.ai.oauth.registry;

import com.pi.ai.oauth.spi.OAuthCredentials;
import com.pi.ai.oauth.spi.OAuthLoginCallbacks;
import com.pi.ai.oauth.spi.OAuthProviderInterface;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 25: OAuthProviderRegistry register/get round-trip
 */
class OAuthProviderRegistryPropertyTest {

    @BeforeProperty
    void resetRegistry() {
        // Clear registry before each property
        OAuthProviderRegistry.reset();
    }

    @Property(tries = 50)
    void registerAndGetRoundTrip(@ForAll("providerIds") String id) {
        OAuthProviderInterface provider = stubProvider(id);
        OAuthProviderRegistry.register(provider);

        OAuthProviderInterface retrieved = OAuthProviderRegistry.get(id);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.id()).isEqualTo(id);
    }

    @Property(tries = 50)
    void registerOverwritesPrevious(@ForAll("providerIds") String id) {
        OAuthProviderInterface first = stubProvider(id, "First");
        OAuthProviderInterface second = stubProvider(id, "Second");

        OAuthProviderRegistry.register(first);
        OAuthProviderRegistry.register(second);

        OAuthProviderInterface retrieved = OAuthProviderRegistry.get(id);
        assertThat(retrieved).isSameAs(second);
    }

    @Property(tries = 30)
    void unregisterBuiltInRestoresDefault(@ForAll("providerIds") String id) {
        OAuthProviderInterface builtIn = stubProvider(id, "BuiltIn");
        OAuthProviderInterface custom = stubProvider(id, "Custom");

        OAuthProviderRegistry.registerBuiltIn(builtIn);
        OAuthProviderRegistry.register(custom);
        assertThat(OAuthProviderRegistry.get(id).name()).isEqualTo("Custom");

        OAuthProviderRegistry.unregister(id);
        assertThat(OAuthProviderRegistry.get(id).name()).isEqualTo("BuiltIn");
    }

    @Property(tries = 30)
    void unregisterNonBuiltInRemoves(@ForAll("providerIds") String id) {
        OAuthProviderInterface provider = stubProvider(id);
        OAuthProviderRegistry.register(provider);
        assertThat(OAuthProviderRegistry.get(id)).isNotNull();

        OAuthProviderRegistry.unregister(id);
        assertThat(OAuthProviderRegistry.get(id)).isNull();
    }

    @Property(tries = 20)
    void getAllReturnsAllRegistered(@ForAll("providerIdLists") List<String> ids) {
        for (String id : ids) {
            OAuthProviderRegistry.register(stubProvider(id));
        }
        List<OAuthProviderInterface> all = OAuthProviderRegistry.getAll();
        for (String id : ids) {
            assertThat(all.stream().anyMatch(p -> p.id().equals(id))).isTrue();
        }
    }

    @Property(tries = 20)
    void getOAuthApiKeyReturnsNullForMissingCredentials(@ForAll("providerIds") String id) {
        OAuthProviderInterface provider = stubProvider(id);
        OAuthProviderRegistry.register(provider);

        CompletableFuture<OAuthProviderRegistry.OAuthApiKeyResult> future =
                OAuthProviderRegistry.getOAuthApiKey(id, Map.of());
        OAuthProviderRegistry.OAuthApiKeyResult result = future.join();
        assertThat(result).isNull();
    }

    @Property(tries = 20)
    void getOAuthApiKeyReturnsKeyForValidCredentials(@ForAll("providerIds") String id) {
        OAuthProviderInterface provider = stubProvider(id);
        OAuthProviderRegistry.register(provider);

        // Non-expired credentials (expires far in the future)
        OAuthCredentials creds = new OAuthCredentials("refresh-tok", "access-tok",
                System.currentTimeMillis() + 3600_000);
        Map<String, OAuthCredentials> credsMap = Map.of(id, creds);

        CompletableFuture<OAuthProviderRegistry.OAuthApiKeyResult> future =
                OAuthProviderRegistry.getOAuthApiKey(id, credsMap);
        OAuthProviderRegistry.OAuthApiKeyResult result = future.join();
        assertThat(result).isNotNull();
        assertThat(result.apiKey()).isEqualTo("access-tok");
        assertThat(result.newCredentials()).isSameAs(creds);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> providerIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
    }

    @Provide
    Arbitrary<List<String>> providerIdLists() {
        return providerIds().list().ofMinSize(1).ofMaxSize(5).uniqueElements();
    }

    // --- Stub provider ---

    private static OAuthProviderInterface stubProvider(String id) {
        return stubProvider(id, "Stub-" + id);
    }

    private static OAuthProviderInterface stubProvider(String id, String name) {
        return new OAuthProviderInterface() {
            @Override public String id() { return id; }
            @Override public String name() { return name; }
            @Override public CompletableFuture<OAuthCredentials> login(OAuthLoginCallbacks cb) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public CompletableFuture<OAuthCredentials> refreshToken(OAuthCredentials c) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public String getApiKey(OAuthCredentials c) { return c.getAccess(); }
        };
    }
}
