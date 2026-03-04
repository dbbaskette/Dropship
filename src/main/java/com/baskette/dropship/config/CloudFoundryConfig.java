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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

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
    ReactorCloudFoundryClient cloudFoundryClient(DefaultConnectionContext connectionContext,
                                                 TokenProvider tokenProvider) {
        return ReactorCloudFoundryClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();
    }

    @Bean
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
