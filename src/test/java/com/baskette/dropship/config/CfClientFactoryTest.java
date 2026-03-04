package com.baskette.dropship.config;

import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class CfClientFactoryTest {

    @Mock
    private ReactorCloudFoundryClient clientA;

    @Mock
    private ReactorCloudFoundryClient clientB;

    private CfClientFactory factory;

    @BeforeEach
    void setUp() {
        DropshipProperties properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        factory = new CfClientFactory(properties);
    }

    @AfterEach
    void tearDown() {
        CfClientFactory.clearCurrentSessionId();
    }

    @Test
    void separateSessionsRetrieveOwnClients() {
        factory.registerClient("session-A", clientA);
        factory.registerClient("session-B", clientB);

        CfClientFactory.setCurrentSessionId("session-A");
        assertThat(factory.getClientForCurrentSession()).isSameAs(clientA);

        CfClientFactory.setCurrentSessionId("session-B");
        assertThat(factory.getClientForCurrentSession()).isSameAs(clientB);
    }

    @Test
    void noSessionIdThrowsIllegalState() {
        assertThatThrownBy(() -> factory.getClientForCurrentSession())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CF credentials found for this session");
    }

    @Test
    void unregisteredSessionThrowsIllegalState() {
        CfClientFactory.setCurrentSessionId("unknown-session");
        assertThatThrownBy(() -> factory.getClientForCurrentSession())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CF credentials found for this session");
    }

    @Test
    void removeClientPreventsAccess() {
        factory.registerClient("session-A", clientA);
        factory.removeClient("session-A");

        CfClientFactory.setCurrentSessionId("session-A");
        assertThatThrownBy(() -> factory.getClientForCurrentSession())
                .isInstanceOf(IllegalStateException.class);
    }
}
