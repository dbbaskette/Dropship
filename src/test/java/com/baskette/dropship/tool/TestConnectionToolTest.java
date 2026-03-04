package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.ConnectionTestResult;
import com.baskette.dropship.service.SpaceResolver;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestConnectionToolTest {

    @Mock
    private CfClientFactory cfClientFactory;

    @Mock
    private SpaceResolver spaceResolver;

    @Mock
    private ReactorCloudFoundryClient cfClient;

    private TestConnectionTool testConnectionTool;

    private static final DropshipProperties PROPS = new DropshipProperties(
            "test-org", "test-space", "https://api.test.example.com",
            0, 0, 0, 0, 0, 0, null
    );

    @BeforeEach
    void setUp() {
        testConnectionTool = new TestConnectionTool(cfClientFactory, spaceResolver, PROPS);
    }

    @Test
    void successReturnsAllFields() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
        when(spaceResolver.resolveSpace(cfClient)).thenReturn(Mono.just("space-guid-123"));

        ConnectionTestResult result = testConnectionTool.testConnection();

        assertThat(result.success()).isTrue();
        assertThat(result.apiHost()).isEqualTo("api.test.example.com");
        assertThat(result.org()).isEqualTo("test-org");
        assertThat(result.space()).isEqualTo("test-space");
        assertThat(result.spaceGuid()).isEqualTo("space-guid-123");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void noSessionReturnsError() {
        when(cfClientFactory.getClientForCurrentSession())
                .thenThrow(new IllegalStateException(
                        "No CF credentials found for this session. Call connect_cf first."));

        ConnectionTestResult result = testConnectionTool.testConnection();

        assertThat(result.success()).isFalse();
        assertThat(result.apiHost()).isEqualTo("api.test.example.com");
        assertThat(result.spaceGuid()).isNull();
        assertThat(result.errorMessage()).contains("No CF credentials found");
    }

    @Test
    void orgNotFoundReturnsError() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
        when(spaceResolver.resolveSpace(cfClient))
                .thenReturn(Mono.error(new IllegalStateException("Organization not found: bad-org")));

        ConnectionTestResult result = testConnectionTool.testConnection();

        assertThat(result.success()).isFalse();
        assertThat(result.org()).isEqualTo("test-org");
        assertThat(result.spaceGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Organization not found: bad-org");
    }

    @Test
    void spaceNotFoundReturnsError() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
        when(spaceResolver.resolveSpace(cfClient))
                .thenReturn(Mono.error(new IllegalStateException(
                        "Space not found: bad-space in organization: test-org")));

        ConnectionTestResult result = testConnectionTool.testConnection();

        assertThat(result.success()).isFalse();
        assertThat(result.space()).isEqualTo("test-space");
        assertThat(result.spaceGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Space not found: bad-space in organization: test-org");
    }

    @Test
    void authFailureReturnsError() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
        when(spaceResolver.resolveSpace(cfClient))
                .thenReturn(Mono.error(new RuntimeException("401 Unauthorized")));

        ConnectionTestResult result = testConnectionTool.testConnection();

        assertThat(result.success()).isFalse();
        assertThat(result.apiHost()).isEqualTo("api.test.example.com");
        assertThat(result.spaceGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("401 Unauthorized");
    }
}
