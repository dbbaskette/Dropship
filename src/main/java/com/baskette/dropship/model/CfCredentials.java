package com.baskette.dropship.model;

/**
 * Per-session Cloud Foundry credentials and target coordinates.
 */
public record CfCredentials(
        String apiUrl,
        String org,
        String space,
        String username,
        String password,
        String clientId,
        String clientSecret
) {
}
