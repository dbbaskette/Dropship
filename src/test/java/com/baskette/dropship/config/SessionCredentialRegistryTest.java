package com.baskette.dropship.config;

import com.baskette.dropship.model.CfCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCredentialRegistryTest {

    private SessionCredentialRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionCredentialRegistry();
    }

    @Test
    void registerAndLookupReturnsCredentials() {
        String sessionId = UUID.randomUUID().toString();
        CfCredentials creds = new CfCredentials(
                "https://api.cf.example.com", "my-org", "my-space",
                "user", "pass", null, null);

        registry.register(sessionId, creds);

        assertThat(registry.lookup(sessionId)).isEqualTo(creds);
    }

    @Test
    void lookupThrowsSessionNotFoundExceptionForUnknownSession() {
        String sessionId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> registry.lookup(sessionId))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessageContaining(sessionId);
    }

    @Test
    void removeDeletesSession() {
        String sessionId = UUID.randomUUID().toString();
        CfCredentials creds = new CfCredentials(
                "https://api.cf.example.com", "org", "space",
                null, null, "client-id", "client-secret");

        registry.register(sessionId, creds);
        registry.remove(sessionId);

        assertThatThrownBy(() -> registry.lookup(sessionId))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void removeNonexistentSessionIsNoOp() {
        registry.remove("nonexistent");
        // no exception expected
    }

    @Test
    void concurrentRegistrationAndLookup() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String sessionId = "session-" + idx;
                CfCredentials creds = new CfCredentials(
                        "https://api.cf.example.com", "org-" + idx, "space-" + idx,
                        "user-" + idx, "pass-" + idx, null, null);

                registry.register(sessionId, creds);
                CfCredentials retrieved = registry.lookup(sessionId);
                assertThat(retrieved).isEqualTo(creds);
                return null;
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();
    }

    @Test
    void registerOverwritesPreviousCredentials() {
        String sessionId = UUID.randomUUID().toString();
        CfCredentials first = new CfCredentials(
                "https://api1.cf.example.com", "org1", "space1",
                "user1", "pass1", null, null);
        CfCredentials second = new CfCredentials(
                "https://api2.cf.example.com", "org2", "space2",
                null, null, "client-id", "client-secret");

        registry.register(sessionId, first);
        registry.register(sessionId, second);

        assertThat(registry.lookup(sessionId)).isEqualTo(second);
    }
}
