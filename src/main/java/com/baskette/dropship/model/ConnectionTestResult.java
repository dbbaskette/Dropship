package com.baskette.dropship.model;

public record ConnectionTestResult(
        boolean success,
        String apiHost,
        String username,
        String org,
        String space,
        String spaceGuid,
        String errorMessage
) {
}
