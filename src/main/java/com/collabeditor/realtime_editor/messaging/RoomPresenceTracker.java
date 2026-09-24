package com.collabeditor.realtime_editor.messaging;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tracks, across all app instances, which instances currently have Yjs peers in each room.
 * <p>
 * <b>Why it exists:</b> the first client to open a room seeds the shared document from the
 * latest snapshot. Each instance only sees its own sockets, so with several instances a
 * client could be "first" on its instance while others are already editing elsewhere.
 * Seeding again would make Yjs merge two copies of every file. This tracker lets the relay
 * ask "is anyone in this room on another instance?" before telling a client it is first.
 *
 * <h2>Storage</h2>
 * One sorted set per room, {@code collab:presence:<roomId>}: member = instance id,
 * score = the epoch-millis moment that entry expires. An instance adds itself when its first
 * local peer joins, removes itself when its last local peer leaves, and a heartbeat refreshes
 * its entries every {@link #DEFAULT_HEARTBEAT}. If an instance crashes, its entries lapse
 * after {@link #DEFAULT_ENTRY_TTL} without anyone cleaning up. Scores use Redis server time
 * ({@code TIME} inside the script), so clock skew between app hosts does not matter, and the
 * check-and-register step is a single atomic script, so two instances receiving a room's
 * first peers simultaneously cannot both conclude they are alone.
 *
 * <h2>Failure behaviour</h2>
 * If Redis is unreachable, {@link #markActive} reports "no other instance". That is the
 * correct answer for an outage: fanout is down too, so every instance is effectively on its
 * own. After an error, Redis is skipped for {@link #REDIS_COOLDOWN}.
 *
 * <h2>Shutdown</h2>
 * On graceful shutdown the instance withdraws from every room, so other instances don't
 * wait out the TTL believing it still has peers. This must happen while Redis is still
 * connected, and Spring stops lifecycle beans (including the Redis connection factory, at
 * phase 0) <i>before</i> it runs {@code @PreDestroy} callbacks. So the tracker is itself a
 * {@link SmartLifecycle} at {@link #LIFECYCLE_PHASE}: it stops after the web server (so no new
 * joins re-register a room) and just before the connection factory.
 */
@Slf4j
@Component
public class RoomPresenceTracker implements SmartLifecycle {

    static final String KEY_PREFIX = "collab:presence:";
    static final Duration DEFAULT_ENTRY_TTL = Duration.ofSeconds(15);
    static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(5);
    static final Duration REDIS_COOLDOWN = Duration.ofSeconds(5);

    /** Stops just before {@code LettuceConnectionFactory} (phase 0); higher phases stop first. */
    static final int LIFECYCLE_PHASE = 1;

    /**
     * KEYS[1] = presence key, ARGV[1] = instance id, ARGV[2] = entry TTL in ms.
     * Drops expired entries, counts live entries of <i>other</i> instances, then
     * registers/refreshes this instance. Returns that count.
     */
    static final DefaultRedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>(
            "local t = redis.call('TIME')\n"
                    + "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)\n"
                    + "local ttl = tonumber(ARGV[2])\n"
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)\n"
                    + "local others = redis.call('ZCARD', KEYS[1])\n"
                    + "if redis.call('ZSCORE', KEYS[1], ARGV[1]) then others = others - 1 end\n"
                    + "redis.call('ZADD', KEYS[1], now + ttl, ARGV[1])\n"
                    + "redis.call('PEXPIRE', KEYS[1], ttl)\n"
                    + "return others",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String instanceId;
    private final Duration entryTtl;
    private final Duration heartbeatInterval;
    private final Clock clock;

    private volatile Supplier<Collection<String>> activeRooms = List::of;
    private ScheduledExecutorService scheduler;

    /** Epoch millis until which Redis is considered down; volatile for cross-thread visibility. */
    private volatile long redisDownUntil = 0L;

    private volatile boolean running;
    /** Once stopped, the tracker never touches Redis again (the connection is going away). */
    private volatile boolean stopped;

    @Autowired
    public RoomPresenceTracker(StringRedisTemplate redisTemplate,
                               @Qualifier("instanceId") String instanceId) {
        this(redisTemplate, instanceId, DEFAULT_ENTRY_TTL, DEFAULT_HEARTBEAT, Clock.systemUTC());
    }

    /** Test seam: lets tests shorten the TTL/heartbeat and control time. */
    RoomPresenceTracker(StringRedisTemplate redisTemplate, String instanceId, Duration entryTtl,
                        Duration heartbeatInterval, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.instanceId = instanceId;
        this.entryTtl = entryTtl;
        this.heartbeatInterval = heartbeatInterval;
        this.clock = clock;
    }

    /**
     * Registers this instance as having peers in {@code roomId}.
     *
     * @return {@code true} if at least one <i>other</i> instance also has live peers in the
     *         room; {@code false} if none do or Redis cannot be reached
     */
    public boolean markActive(String roomId) {
        if (stopped || isRedisDegraded()) {
            return false;
        }
        try {
            Long others = redisTemplate.execute(TOUCH_SCRIPT, List.of(KEY_PREFIX + roomId),
                    instanceId, String.valueOf(entryTtl.toMillis()));
            return others != null && others > 0;
        } catch (RuntimeException e) {
            markRedisDown(e.getMessage());
            return false;
        }
    }

    /** Removes this instance from {@code roomId} (its last local peer left). Never throws. */
    public void markInactive(String roomId) {
        if (!stopped) {
            withdraw(roomId);
        }
    }

    private void withdraw(String roomId) {
        if (isRedisDegraded()) {
            return; // the entry will lapse on its own
        }
        try {
            redisTemplate.opsForZSet().remove(KEY_PREFIX + roomId, instanceId);
        } catch (RuntimeException e) {
            markRedisDown(e.getMessage());
        }
    }

    /**
     * Supplies the rooms that currently have local peers and starts the heartbeat that keeps
     * this instance's entries alive. Called once by the relay at startup.
     */
    public synchronized void trackActiveRooms(Supplier<Collection<String>> supplier) {
        this.activeRooms = supplier;
        if (scheduler == null && !stopped) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "room-presence-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long periodMs = heartbeatInterval.toMillis();
            scheduler.scheduleWithFixedDelay(this::heartbeat, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Re-registers every active room right away, ignoring any error cooldown. Called when
     * Redis is known to be reachable again: a restarted Redis has lost every presence set,
     * and waiting for the next heartbeat would leave a window in which a newcomer on another
     * instance is wrongly told it is first.
     */
    public void refreshNow() {
        redisDownUntil = 0L;
        heartbeat();
    }

    /** Refreshes this instance's entry for every room with local peers. Visible for tests. */
    void heartbeat() {
        try {
            for (String roomId : List.copyOf(activeRooms.get())) {
                if (isRedisDegraded()) {
                    return;
                }
                markActive(roomId);
            }
        } catch (RuntimeException e) {
            // A scheduled task that throws is silently cancelled; never let that happen.
            log.warn("Presence heartbeat failed: {}", e.getMessage());
        }
    }

    /**
     * Stops the heartbeat and withdraws this instance from every room (best effort).
     * Idempotent; runs from {@link #stop()} during context shutdown, with {@code @PreDestroy}
     * as a fallback.
     */
    @PreDestroy
    public synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            for (String roomId : List.copyOf(activeRooms.get())) {
                withdraw(roomId);
            }
        } catch (RuntimeException e) {
            log.debug("Presence cleanup on shutdown failed: {}", e.getMessage());
        }
    }

    // ── SmartLifecycle ────────────────────────────

    @Override
    public void start() {
        running = !stopped;
    }

    @Override
    public void stop() {
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    // ── Redis degraded guard ──────────────────────

    private boolean isRedisDegraded() {
        return clock.millis() < redisDownUntil;
    }

    private void markRedisDown(String reason) {
        redisDownUntil = clock.millis() + REDIS_COOLDOWN.toMillis();
        log.warn("Redis room presence unavailable ({}); treating rooms as local-only for {}s",
                reason, REDIS_COOLDOWN.toSeconds());
    }
}