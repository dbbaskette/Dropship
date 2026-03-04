package com.baskette.dropship.config;

import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CfClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CfClientFactory.class);

    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private final ConcurrentMap<String, ReactorCloudFoundryClient> sessionClients = new ConcurrentHashMap<>();
    private final DropshipProperties properties;

    public CfClientFactory(DropshipProperties properties) {
        this.properties = properties;
    }

    public static void setCurrentSessionId(String sessionId) {
        currentSessionId.set(sessionId);
    }

    public static void clearCurrentSessionId() {
        currentSessionId.remove();
    }

    public ReactorCloudFoundryClient getClientForCurrentSession() {
        String sessionId = currentSessionId.get();
        if (sessionId == null) {
            throw new IllegalStateException(
                    "No CF credentials found for this session. Call connect_cf first.");
        }
        ReactorCloudFoundryClient client = sessionClients.get(sessionId);
        if (client == null) {
            throw new IllegalStateException(
                    "No CF credentials found for this session. Call connect_cf first.");
        }
        return client;
    }

    public DefaultCloudFoundryOperations getOperationsForCurrentSession() {
        ReactorCloudFoundryClient client = getClientForCurrentSession();
        return DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(client)
                .organization(properties.sandboxOrg())
                .space(properties.sandboxSpace())
                .build();
    }

    public void registerClient(String sessionId, ReactorCloudFoundryClient client) {
        log.info("Registered CF client for session: {}", sessionId);
        sessionClients.put(sessionId, client);
    }

    public void removeClient(String sessionId) {
        log.info("Removed CF client for session: {}", sessionId);
        sessionClients.remove(sessionId);
    }
}
