package com.collabeditor.realtime_editor.messaging;

import com.collabeditor.realtime_editor.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRoomBrokerTest {

    private static final String MY_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_ID = "22222222-2222-4222-8222-222222222222";

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    private MutableClock clock;
    private RedisRoomBroker broker;

    /** Reconnect runs executed synchronously but only when the test drains them. */
    private final List<Runnable> queuedReconnects = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        broker = new RedisRoomBroker(connectionFactory, MY_ID, clock, queuedReconnects::add);
    }

    private void drainReconnects() {
        List<Runnable> runs = new ArrayList<>(queuedReconnects);
        queuedReconnects.clear();
        runs.forEach(Runnable::run);
    }

    private static byte[] body(String senderId, byte[] payload) {
        byte[] id = senderId.getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(id, id.length + payload.length);
        System.arraycopy(payload, 0, out, id.length, payload.length);
        return out;
    }

    private static DefaultMessage message(String channel, byte[] body) {
        return new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8), body);
    }

    // ── Publishing ────────────────────────────────

    @Test
    @DisplayName("publishYjs - sends <instanceId><frame> on collab:yjs:<roomId> and closes the connection")
    void publishYjs_shouldPrefixInstanceIdAndUseRoomChannel() {
        when(connectionFactory.getConnection()).thenReturn(connection);

        broker.publishYjs("room-1", new byte[]{1, 2, 3});

        ArgumentCaptor<byte[]> channel = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(channel.capture(), sent.capture());
        verify(connection).close();
        assertThat(new String(channel.getValue(), StandardCharsets.UTF_8)).isEqualTo("collab:yjs:room-1");
        assertThat(sent.getValue()).isEqualTo(body(MY_ID, new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("publishChat - sends <instanceId><utf-8 json> on collab:chat:<roomId>")
    void publishChat_shouldEncodeUtf8() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        String json = "{\"content\":\"héllo ✓\"}";

        broker.publishChat("room-1", json);

        ArgumentCaptor<byte[]> channel = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(channel.capture(), sent.capture());
        assertThat(new String(channel.getValue(), StandardCharsets.UTF_8)).isEqualTo("collab:chat:room-1");
        assertThat(sent.getValue()).isEqualTo(body(MY_ID, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("publish - a Redis failure is swallowed and publishing pauses for the cooldown")
    void publish_failureShouldBeSwallowedAndTriggerCooldown() {
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("down"));

        broker.publishYjs("r", new byte[]{1});   // fails, must not throw
        broker.publishYjs("r", new byte[]{2});   // inside cooldown: Redis not touched
        broker.publishChat("r", "{}");

        verify(connectionFactory, times(1)).getConnection();
    }

    @Test
    @DisplayName("publish - after the cooldown, a successful publish resumes fanout and fires reconnect listeners")
    void publish_recoveryShouldFireReconnect() {
        AtomicInteger reconnects = new AtomicInteger();
        broker.onReconnect(reconnects::incrementAndGet);
        when(connectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(connection);

        broker.publishYjs("r", new byte[]{1});                        // fails
        clock.advance(RedisRoomBroker.PUBLISH_COOLDOWN.plus(Duration.ofMillis(1)));
        broker.publishYjs("r", new byte[]{2});                        // succeeds
        drainReconnects();

        verify(connection).publish(any(byte[].class), any(byte[].class));
        assertThat(reconnects).hasValue(1);
    }

    // ── Receiving ─────────────────────────────────

    @Test
    @DisplayName("onMessage - drops this instance's own messages (no self-echo)")
    void onMessage_shouldDropOwnMessages() {
        List<String> received = new ArrayList<>();
        broker.onYjs((room, data) -> received.add(room));
        broker.onChat((room, json) -> received.add(room));

        broker.onMessage(message("collab:yjs:r", body(MY_ID, new byte[]{1})), null);
        broker.onMessage(message("collab:chat:r", body(MY_ID, "{}".getBytes(StandardCharsets.UTF_8))), null);

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("onMessage - routes other instances' Yjs frames to the Yjs receiver, header stripped")
    void onMessage_shouldRouteYjs() {
        List<Object[]> received = new ArrayList<>();
        broker.onYjs((room, data) -> received.add(new Object[]{room, data}));
        broker.onChat((room, json) -> received.add(new Object[]{"CHAT"}));

        broker.onMessage(message("collab:yjs:room-7", body(OTHER_ID, new byte[]{1, 9, 8})), null);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)[0]).isEqualTo("room-7");
        assertThat((byte[]) received.get(0)[1]).containsExactly(1, 9, 8);
    }

    @Test
    @DisplayName("onMessage - routes other instances' chat to the chat receiver as a UTF-8 string")
    void onMessage_shouldRouteChat() {
        List<String> received = new ArrayList<>();
        broker.onYjs((room, data) -> received.add("YJS"));
        broker.onChat((room, json) -> received.add(room + "|" + json));
        String json = "{\"content\":\"héllo ✓\"}";

        broker.onMessage(message("collab:chat:room-7", body(OTHER_ID, json.getBytes(StandardCharsets.UTF_8))), null);

        assertThat(received).containsExactly("room-7|" + json);
    }

    @Test
    @DisplayName("onMessage - ignores malformed bodies, empty room ids and unknown channels")
    void onMessage_shouldIgnoreMalformed() {
        List<String> received = new ArrayList<>();
        broker.onYjs((room, data) -> received.add(room));
        broker.onChat((room, json) -> received.add(room));

        broker.onMessage(message("collab:yjs:r", new byte[]{1, 2, 3}), null);             // shorter than header
        broker.onMessage(message("collab:yjs:", body(OTHER_ID, new byte[]{1})), null);   // empty room id
        broker.onMessage(message("other:thing", body(OTHER_ID, new byte[]{1})), null);   // unknown channel
        broker.onMessage(message("collab:chat:r", new byte[0]), null);                     // empty body

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("onMessage - an exception in a receiver does not propagate to the container")
    void onMessage_receiverExceptionShouldBeContained() {
        broker.onYjs((room, data) -> { throw new IllegalStateException("boom"); });

        broker.onMessage(message("collab:yjs:r", body(OTHER_ID, new byte[]{1})), null);
        // reaching here means it was contained
    }

    @Test
    @DisplayName("onMessage - with no receiver registered, messages are dropped quietly")
    void onMessage_withoutReceiverShouldNotFail() {
        broker.onMessage(message("collab:yjs:r", body(OTHER_ID, new byte[]{1})), null);
        broker.onMessage(message("collab:chat:r", body(OTHER_ID, new byte[]{'x'})), null);
    }

    // ── Subscription / reconnect ──────────────────

    @Test
    @DisplayName("onPatternSubscribed - the Yjs pattern fires reconnect listeners; the chat pattern does not")
    void onPatternSubscribed_shouldFireOnlyForYjsPattern() {
        AtomicInteger reconnects = new AtomicInteger();
        broker.onReconnect(reconnects::incrementAndGet);

        broker.onPatternSubscribed(RedisRoomBroker.CHAT_PATTERN.getBytes(StandardCharsets.UTF_8), 1);
        drainReconnects();
        assertThat(reconnects).hasValue(0);

        broker.onPatternSubscribed(RedisRoomBroker.YJS_PATTERN.getBytes(StandardCharsets.UTF_8), 2);
        drainReconnects();
        assertThat(reconnects).hasValue(1);
    }

    @Test
    @DisplayName("Reconnect triggers that arrive before the queued run starts are coalesced into one run")
    void reconnect_shouldCoalesceBursts() {
        AtomicInteger reconnects = new AtomicInteger();
        broker.onReconnect(reconnects::incrementAndGet);
        byte[] yjs = RedisRoomBroker.YJS_PATTERN.getBytes(StandardCharsets.UTF_8);

        broker.onPatternSubscribed(yjs, 1);
        broker.onPatternSubscribed(yjs, 1);
        broker.onPatternSubscribed(yjs, 1);

        assertThat(queuedReconnects).hasSize(1);
        drainReconnects();
        assertThat(reconnects).hasValue(1);

        broker.onPatternSubscribed(yjs, 1);   // after the run started, a new trigger queues again
        assertThat(queuedReconnects).hasSize(1);
    }

    @Test
    @DisplayName("A failing reconnect listener does not stop the others")
    void reconnect_listenerFailureShouldBeIsolated() {
        AtomicInteger ran = new AtomicInteger();
        broker.onReconnect(() -> { throw new IllegalStateException("boom"); });
        broker.onReconnect(ran::incrementAndGet);

        broker.onPatternSubscribed(RedisRoomBroker.YJS_PATTERN.getBytes(StandardCharsets.UTF_8), 1);
        drainReconnects();

        assertThat(ran).hasValue(1);
    }

    // ── Construction ──────────────────────────────

    @Test
    @DisplayName("Rejects an instance id that is not a 36-character ASCII UUID")
    void constructor_shouldValidateInstanceId() {
        assertThatThrownBy(() -> new RedisRoomBroker(connectionFactory, "short", clock, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRoomBroker(connectionFactory, null, clock, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRoomBroker(connectionFactory,
                "é1111111-1111-4111-8111-11111111111", clock, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        verify(connectionFactory, never()).getConnection();
    }
}