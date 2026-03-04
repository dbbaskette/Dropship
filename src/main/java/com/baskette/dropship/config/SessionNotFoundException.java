package com.baskette.dropship.config;

public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("No credentials registered for session: " + sessionId);
    }
}
