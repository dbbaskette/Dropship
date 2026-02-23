package com.baskette.dropship.config;

import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.ClientCredentialsGrantTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class CloudFoundryConfig {

    @Bean
    DefaultConnectionContext connectionContext(DropshipProperties properties,
                                              @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        return DefaultConnectionContext.builder()
                .apiHost(extractHost(properties.cfApiUrl()))
                .skipSslValidation(skipSslValidation)
                .build();
    }

    @Bean
    ClientCredentialsGrantTokenProvider tokenProvider(
            @Value("${cf.client-id}") String clientId,
            @Value("${cf.client-secret}") String clientSecret) {
        return ClientCredentialsGrantTokenProvider.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
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
