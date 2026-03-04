package com.baskette.dropship.config;

import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.ClientCredentialsGrantTokenProvider;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;

/**
 * Cloud Foundry connection configuration with dual-mode authentication.
 * <p>
 * Supports two credential types, auto-detected via environment variables:
 * <ul>
 *   <li>Client credentials (CF_CLIENT_ID / CF_CLIENT_SECRET) — takes precedence via @Primary</li>
 *   <li>Password grant (CF_USERNAME / CF_PASSWORD) — used when client credentials are absent</li>
 * </ul>
 * If neither credential type is configured, CF beans are not created and the app starts without CF connectivity.
 * <p>
 * Note: {@code @ConditionalOnExpression} is used instead of {@code @ConditionalOnProperty} because
 * application.yml defines empty defaults (e.g. {@code cf.client-id: ${CF_CLIENT_ID:}}), which means
 * the property always exists as an empty string. {@code @ConditionalOnProperty} treats any non-"false"
 * value (including empty string) as present, so it cannot distinguish "set" from "empty default".
 */
@Configuration
public class CloudFoundryConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundryConfig.class);

    @Bean
    DefaultConnectionContext connectionContext(DropshipProperties properties,
                                              @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        return DefaultConnectionContext.builder()
                .apiHost(extractHost(properties.cfApiUrl()))
                .skipSslValidation(skipSslValidation)
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnExpression("!'${cf.client-id:}'.isEmpty()")
    ClientCredentialsGrantTokenProvider clientCredentialsTokenProvider(
            @Value("${cf.client-id}") String clientId,
            @Value("${cf.client-secret}") String clientSecret) {
        log.info("Using client credentials authentication (cf.client-id)");
        return ClientCredentialsGrantTokenProvider.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    @Bean
    @ConditionalOnExpression("!'${cf.username:}'.isEmpty()")
    @ConditionalOnMissingBean(TokenProvider.class)
    PasswordGrantTokenProvider passwordGrantTokenProvider(
            @Value("${cf.username}") String username,
            @Value("${cf.password}") String password) {
        log.info("Using password grant authentication (cf.username)");
        return PasswordGrantTokenProvider.builder()
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    @ConditionalOnBean(TokenProvider.class)
    ReactorCloudFoundryClient cloudFoundryClient(DefaultConnectionContext connectionContext,
                                                 TokenProvider tokenProvider) {
        return ReactorCloudFoundryClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();
    }

    @Bean
    @ConditionalOnBean(TokenProvider.class)
    DefaultCloudFoundryOperations cloudFoundryOperations(ReactorCloudFoundryClient cloudFoundryClient,
                                                         DropshipProperties properties) {
        return DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(cloudFoundryClient)
                .organization(properties.sandboxOrg())
                .space(properties.sandboxSpace())
                .build();
    }

    String extractHost(String apiUrl) {
        return URI.create(apiUrl).getHost();
    }
}
