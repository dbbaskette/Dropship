package com.baskette.dropship.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class StaticCredentialsSecurityConfigurationTest {

    @Test
    void securityWebFilterChainBeanIsCreated() {
        StaticCredentialsSecurityConfiguration config = new StaticCredentialsSecurityConfiguration();
        ServerHttpSecurity http = ServerHttpSecurity.http();

        SecurityWebFilterChain filterChain = config.securityWebFilterChain(http);

        assertThat(filterChain).isNotNull();
    }

    @Test
    void filterChainPermitsUnauthenticatedRequests() {
        StaticCredentialsSecurityConfiguration config = new StaticCredentialsSecurityConfiguration();
        ServerHttpSecurity http = ServerHttpSecurity.http();
        SecurityWebFilterChain filterChain = config.securityWebFilterChain(http);

        MockServerHttpRequest request = MockServerHttpRequest.get("/mcp").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filterChain.matches(exchange))
                .expectNext(true)
                .verifyComplete();
    }
}
