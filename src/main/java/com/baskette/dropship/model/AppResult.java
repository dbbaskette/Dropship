package com.baskette.dropship.model;

public record AppResult(
        String appGuid,
        String appName,
        String routeUrl,
        String routeGuid,
        State state,
        String errorMessage
) {
    public enum State { STARTING, RUNNING, CRASHED, STOPPED }
}
