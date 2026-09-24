package com.collabeditor.realtime_editor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link RedisMessageListenerContainer} that tolerates Redis being unavailable at startup.
 * <p>
 * The stock container recovers from connections lost <i>after</i> it has subscribed, but if
 * Redis is down when it first starts, {@code start()} throws, which fails the whole
 * application context, and the container is left marked running yet never listening, so it
 * never subscribes even once Redis appears. This subclass catches that first failure,
 * resets the container, and retries {@code start()} in the background every
 * {@code retryIntervalMs} until it succeeds (or the container is stopped). From then on, the
 * stock recovery handles any later outages.
 */
@Slf4j
public class ResilientRedisMessageListenerContainer extends RedisMessageListenerContainer {

    private final long retryIntervalMs;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redis-pubsub-start-retry");
        t.setDaemon(true);
        return t;
    });

    private final Object lifecycleLock = new Object();
    private boolean startRequested;
    /** True while this container itself is inside start/stop (so its own stop() calls don't cancel retries). */
    private boolean internalCall;
    private boolean loggedFailure;
    private ScheduledFuture<?> pendingRetry;

    public ResilientRedisMessageListenerContainer(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            startRequested = true;
            attemptStart();
        }
    }

    /** Must be called while holding {@link #lifecycleLock}. */
    private void attemptStart() {
        if (!startRequested) {
            return;
        }
        internalCall = true;
        try {
            super.start();
            if (loggedFailure) {
                log.info("Redis pub/sub subscription established; cross-instance fanout active");
                loggedFailure = false;
            }
        } catch (RuntimeException e) {
            // Undo the half-started state (running but not listening) so the next start() subscribes.
            try {
                super.stop();
            } catch (RuntimeException ignored) {
                // nothing further to clean up
            }

            if (!loggedFailure) {
                log.warn("Redis pub/sub unavailable ({}); running without cross-instance fanout, "
                        + "retrying every {} ms", rootMessage(e), retryIntervalMs);
                loggedFailure = true;
            } else {
                log.debug("Redis pub/sub still unavailable: {}", rootMessage(e));
            }
            scheduleRetry();
        } finally {
            internalCall = false;
        }
    }

    private void scheduleRetry() {
        if (retryScheduler.isShutdown()) {
            return;
        }
        pendingRetry = retryScheduler.schedule(() -> {
            synchronized (lifecycleLock) {
                pendingRetry = null;
                attemptStart();
            }
        }, retryIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        cancelRetries();
        super.stop();
    }

    @Override
    public void stop(Runnable callback) {
        cancelRetries();
        super.stop(callback);
    }

    @Override
    public void destroy() throws Exception {
        cancelRetries();
        retryScheduler.shutdownNow();
        super.destroy();
    }

    /**
     * An external stop cancels any pending retry. Calls made by this container during a start
     * attempt (same thread, lock already held) do not.
     */
    private void cancelRetries() {
        synchronized (lifecycleLock) {
            if (internalCall) {
                return;
            }
            startRequested = false;
            if (pendingRetry != null) {
                pendingRetry.cancel(false);
                pendingRetry = null;
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}