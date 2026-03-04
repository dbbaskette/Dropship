package com.baskette.dropship.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StaticCredentialsSecurityConfigurationTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void permitsUnauthenticatedRequests() {
        webTestClient.get().uri("/nonexistent")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value())
                                .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
    }
}
