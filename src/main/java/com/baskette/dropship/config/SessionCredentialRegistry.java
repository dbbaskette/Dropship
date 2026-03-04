package com.baskette.dropship.config;

import com.baskette.dropship.model.CfCredentials;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionCredentialRegistry {

    private final ConcurrentHashMap<String, CfCredentials> store = new ConcurrentHashMap<>();

    public void register(String sessionId, CfCredentials credentials) {
        store.put(sessionId, credentials);
    }

    public CfCredentials lookup(String sessionId) {
        CfCredentials credentials = store.get(sessionId);
        if (credentials == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return credentials;
    }

    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
