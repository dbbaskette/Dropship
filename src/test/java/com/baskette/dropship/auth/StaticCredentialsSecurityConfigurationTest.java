package com.baskette.dropship.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.test.StepVerifier;

class StaticCredentialsSecurityConfigurationTest {

    private SecurityWebFilterChain filterChain;

    @BeforeEach
    void setUp() {
        StaticCredentialsSecurityConfiguration config = new StaticCredentialsSecurityConfiguration();
        ServerHttpSecurity http = ServerHttpSecurity.http();
        filterChain = config.securityWebFilterChain(http);
    }

    @Test
    void filterChainPermitsUnauthenticatedRequests() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/mcp").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filterChain.matches(exchange))
                .expectNext(true)
                .verifyComplete();
    }
}
