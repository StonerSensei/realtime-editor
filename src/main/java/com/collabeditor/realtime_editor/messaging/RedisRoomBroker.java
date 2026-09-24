package com.collabeditor.realtime_editor.messaging;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Cross-instance fanout for room traffic over Redis Pub/Sub.
 * <p>
 * Each WebSocket handler delivers a frame to its own local sessions immediately, then calls
 * {@link #publishYjs} / {@link #publishChat} so every <i>other</i> instance can deliver it to
 * its local sessions too.
 *
 * <h2>Wire format</h2>
 * Channels are {@code collab:yjs:<roomId>} and {@code collab:chat:<roomId>}. Every message
 * body is {@code <36-byte instanceId><payload>}: the sender's instance id (an ASCII UUID)
 * followed by the raw Yjs frame bytes or the UTF-8 chat JSON. Because Redis delivers a
 * publish back to the publisher's own subscription as well, {@link #onMessage} drops any
 * message carrying this instance's id, so local sessions never receive a frame twice.
 *
 * <h2>Failure behaviour</h2>
 * Publishing never throws into the caller. After a publish error the broker skips Redis for
 * {@link #PUBLISH_COOLDOWN} (so a Redis outage does not add a connection timeout to every
 * keystroke) and the app keeps working as isolated single instances. Listeners registered
 * with {@link #onReconnect} are invoked when Redis is usable again, either because the
 * subscription was (re-)established or because a publish succeeded after failures, so
 * callers can repair state that diverged while instances were cut off. Those listeners run
 * on the broker's own thread, never on a Redis I/O thread (where a blocking Redis call could
 * deadlock), and bursts of triggers are coalesced into one run.
 */
@Slf4j
@Component
public class RedisRoomBroker implements MessageListener, SubscriptionListener {

    public static final String YJS_CHANNEL_PREFIX = "collab:yjs:";
    public static final String CHAT_CHANNEL_PREFIX = "collab:chat:";
    public static final String YJS_PATTERN = YJS_CHANNEL_PREFIX + "*";
    public static final String CHAT_PATTERN = CHAT_CHANNEL_PREFIX + "*";

    /** Length of the sender-id header: a canonical UUID string, e.g. 8-4-4-4-12 hex digits. */
    public static final int INSTANCE_ID_LENGTH = 36;

    /** How long to skip publishing after a Redis error before trying again. */
    static final Duration PUBLISH_COOLDOWN = Duration.ofSeconds(5);

    private static final byte[] YJS_PATTERN_BYTES = YJS_PATTERN.getBytes(StandardCharsets.UTF_8);

    private final RedisConnectionFactory connectionFactory;
    private final String instanceId;
    private final byte[] instanceIdBytes;
    private final Clock clock;
    private final Executor reconnectExecutor;
    private final ExecutorService ownedExecutor;

    private volatile BiConsumer<String, byte[]> yjsReceiver;
    private volatile BiConsumer<String, String> chatReceiver;
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();

    /** Epoch millis until which publishing is skipped; volatile for cross-thread visibility. */
    private volatile long publishDownUntil = 0L;

    /** True after a publish failed and before the next one succeeds. */
    private final AtomicBoolean publishDegraded = new AtomicBoolean(false);

    /** True while a reconnect run is queued but not started; coalesces bursts of triggers. */
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    @Autowired
    public RedisRoomBroker(RedisConnectionFactory connectionFactory,
                           @Qualifier("instanceId") String instanceId) {
        this(connectionFactory, instanceId, Clock.systemUTC(), null);
    }

    /**
     * Test seam: lets tests control time and run reconnect listeners synchronously.
     * A {@code null} executor means "own a single daemon thread".
     */
    RedisRoomBroker(RedisConnectionFactory connectionFactory, String instanceId, Clock clock,
                    Executor reconnectExecutor) {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId must not be null");
        }
        // Check ASCII explicitly: encoding to US-ASCII would silently turn 'é' into '?'.
        if (instanceId.length() != INSTANCE_ID_LENGTH || !instanceId.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException(
                    "instanceId must be a " + INSTANCE_ID_LENGTH + "-character ASCII UUID, got: " + instanceId);
        }
        this.connectionFactory = connectionFactory;
        this.instanceId = instanceId;
        this.instanceIdBytes = instanceId.getBytes(StandardCharsets.US_ASCII);
        this.clock = clock;
        if (reconnectExecutor != null) {
            this.reconnectExecutor = reconnectExecutor;
            this.ownedExecutor = null;
        } else {
            this.ownedExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redis-broker-reconnect");
                t.setDaemon(true);
                return t;
            });
            this.reconnectExecutor = ownedExecutor;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ── Registration ──────────────────────────────

    /** Registers the receiver for Yjs frames published by other instances: (roomId, frame). */
    public void onYjs(BiConsumer<String, byte[]> receiver) {
        this.yjsReceiver = receiver;
    }

    /** Registers the receiver for chat messages published by other instances: (roomId, json). */
    public void onChat(BiConsumer<String, String> receiver) {
        this.chatReceiver = receiver;
    }

    /** Registers a callback run whenever Redis becomes usable again after being unreachable. */
    public void onReconnect(Runnable listener) {
        reconnectListeners.add(listener);
    }

    public String getInstanceId() {
        return instanceId;
    }

    // ── Publishing ────────────────────────────────

    /** Publishes a Yjs frame to the other instances serving {@code roomId}. Never throws. */
    public void publishYjs(String roomId, byte[] frame) {
        publish(YJS_CHANNEL_PREFIX + roomId, frame);
    }

    /** Publishes a chat message (JSON) to the other instances serving {@code roomId}. Never throws. */
    public void publishChat(String roomId, String json) {
        publish(CHAT_CHANNEL_PREFIX + roomId, json.getBytes(StandardCharsets.UTF_8));
    }

    private void publish(String channel, byte[] payload) {
        if (clock.millis() < publishDownUntil) {
            return; // Redis recently failed: run as a standalone instance for now
        }

        byte[] body = new byte[INSTANCE_ID_LENGTH + payload.length];
        System.arraycopy(instanceIdBytes, 0, body, 0, INSTANCE_ID_LENGTH);
        System.arraycopy(payload, 0, body, INSTANCE_ID_LENGTH, payload.length);

        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.publish(channel.getBytes(StandardCharsets.UTF_8), body);
        } catch (RuntimeException e) {
            publishDownUntil = clock.millis() + PUBLISH_COOLDOWN.toMillis();
            if (publishDegraded.compareAndSet(false, true)) {
                log.warn("Redis publish failed ({}); cross-instance fanout paused, retrying in {}s",
                        e.getMessage(), PUBLISH_COOLDOWN.toSeconds());
            }
            return;
        }

        if (publishDegraded.compareAndSet(true, false)) {
            log.info("Redis publish recovered; resuming cross-instance fanout");
            fireReconnect();
        }
    }

    // ── Receiving ─────────────────────────────────

    /**
     * Called by the listener container for every message on a subscribed pattern.
     * Drops this instance's own messages and routes the rest by channel prefix.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] channelBytes = message.getChannel();
        byte[] body = message.getBody();
        if (channelBytes == null || body == null || body.length < INSTANCE_ID_LENGTH) {
            log.debug("Ignoring malformed pub/sub message");
            return;
        }

        if (Arrays.equals(body, 0, INSTANCE_ID_LENGTH, instanceIdBytes, 0, INSTANCE_ID_LENGTH)) {
            return; // our own publish, already delivered locally
        }

        String channel = new String(channelBytes, StandardCharsets.UTF_8);
        byte[] payload = Arrays.copyOfRange(body, INSTANCE_ID_LENGTH, body.length);

        try {
            if (channel.startsWith(YJS_CHANNEL_PREFIX)) {
                String roomId = channel.substring(YJS_CHANNEL_PREFIX.length());
                BiConsumer<String, byte[]> receiver = yjsReceiver;
                if (!roomId.isEmpty() && receiver != null) {
                    receiver.accept(roomId, payload);
                }
            } else if (channel.startsWith(CHAT_CHANNEL_PREFIX)) {
                String roomId = channel.substring(CHAT_CHANNEL_PREFIX.length());
                BiConsumer<String, String> receiver = chatReceiver;
                if (!roomId.isEmpty() && receiver != null) {
                    receiver.accept(roomId, new String(payload, StandardCharsets.UTF_8));
                }
            } else {
                log.debug("Ignoring message on unexpected channel {}", channel);
            }
        } catch (RuntimeException e) {
            // Never let one bad delivery kill the dispatch thread.
            log.warn("Failed to deliver pub/sub message from {}: {}", channel, e.getMessage());
        }
    }

    /**
     * Called by the listener container each time a pattern subscription is confirmed:
     * at startup, and again after the container recovers from a lost Redis connection.
     * Both cases mean frames may have been missed, so reconnect listeners are fired
     * (they are no-ops when no rooms are active, e.g. at startup).
     */
    @Override
    public void onPatternSubscribed(byte[] pattern, long count) {
        if (Arrays.equals(pattern, YJS_PATTERN_BYTES)) {
            log.info("Subscribed to {} (instance {})", YJS_PATTERN, instanceId);
            fireReconnect();
        }
    }

    private void fireReconnect() {
        if (reconnectListeners.isEmpty() || !reconnectPending.compareAndSet(false, true)) {
            return; // nothing to notify, or a run is already queued and will cover this trigger
        }
        try {
            reconnectExecutor.execute(this::runReconnectListeners);
        } catch (RejectedExecutionException e) {
            reconnectPending.set(false); // shutting down
        }
    }

    private void runReconnectListeners() {
        reconnectPending.set(false);
        for (Runnable listener : reconnectListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("Reconnect listener failed: {}", e.getMessage());
            }
        }
    }
}