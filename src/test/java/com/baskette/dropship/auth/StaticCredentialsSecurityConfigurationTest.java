package com.baskette.dropship.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

class StaticCredentialsSecurityConfigurationTest {

    @Test
    void securityWebFilterChainBeanIsCreated() {
        StaticCredentialsSecurityConfiguration config = new StaticCredentialsSecurityConfiguration();
        ServerHttpSecurity http = ServerHttpSecurity.http();

        SecurityWebFilterChain filterChain = config.securityWebFilterChain(http);

        assertThat(filterChain).isNotNull();
    }
}
