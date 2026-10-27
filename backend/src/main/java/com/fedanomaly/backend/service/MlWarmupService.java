package com.fedanomaly.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wakes the ML service ahead of time. Free hosting puts it to sleep when idle and a cold
 * start takes a minute or more, so we ping /health in the background on startup and when
 * the dashboard loads, instead of waiting for the simulation's first prediction.
 */
@Service
public class MlWarmupService {
    private static final long GIVE_UP_AFTER_MS = 180_000;
    private static final long RETRY_EVERY_MS = 10_000;

    @Value("${ML_SERVICE_URL:http://localhost:5000}")
    private String mlServiceUrl;

    // Long read timeout: a sleeping host may hold the first request until it has started
    private final RestTemplate restTemplate = MlHttp.restTemplate(90_000);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ml-warmup");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean warming = new AtomicBoolean(false);
    private volatile boolean reachable = false;
    private volatile Instant lastSuccess = null;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        wake();
    }

    /** Starts a background warm-up unless one is already running. Never blocks. */
    public void wake() {
        if (!warming.compareAndSet(false, true)) return;
        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                while (System.currentTimeMillis() - start < GIVE_UP_AFTER_MS) {
                    if (ping()) {
                        reachable = true;
                        lastSuccess = Instant.now();
                        System.out.println("ML service is awake at " + mlServiceUrl);
                        return;
                    }
                    reachable = false;
                    Thread.sleep(RETRY_EVERY_MS);
                }
                System.err.println("ML service did not answer at " + mlServiceUrl + " within "
                    + GIVE_UP_AFTER_MS / 1000 + "s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                warming.set(false);
            }
        });
    }

    private boolean ping() {
        try {
            restTemplate.getForObject(mlServiceUrl + "/health", Map.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new HashMap<>();
        s.put("reachable", reachable);
        s.put("warming", warming.get());
        s.put("lastSuccess", lastSuccess != null ? lastSuccess.toString() : null);
        return s;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
