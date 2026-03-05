package com.baskette.dropship.service;

import com.baskette.dropship.model.StagingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@Service
public class BuildTracker {

    private static final Logger log = LoggerFactory.getLogger(BuildTracker.class);

    private final ConcurrentHashMap<String, CompletableFuture<StagingResult>> builds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public String submit(Supplier<StagingResult> work) {
        String buildId = UUID.randomUUID().toString();
        CompletableFuture<StagingResult> future = CompletableFuture.supplyAsync(work, executor);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Build {} failed: {}", buildId, ex.getMessage());
            } else {
                log.info("Build {} completed: success={}", buildId, result.success());
            }
        });
        builds.put(buildId, future);
        log.info("Submitted build {}", buildId);
        return buildId;
    }

    public StagingResult getStatus(String buildId) {
        CompletableFuture<StagingResult> future = builds.get(buildId);
        if (future == null) {
            throw new IllegalArgumentException("Unknown build ID: " + buildId);
        }
        if (!future.isDone()) {
            return new StagingResult(null, null, null, null, null, 0, false,
                    "Build " + buildId + " is still in progress");
        }
        try {
            return future.get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new StagingResult(null, null, null, null, null, 0, false,
                    "Build failed: " + cause.getMessage());
        }
    }
}
