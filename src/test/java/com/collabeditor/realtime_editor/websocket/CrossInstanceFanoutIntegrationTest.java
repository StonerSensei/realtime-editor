package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.config.RedisConfig;
import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.messaging.RedisRoomBroker;
import com.collabeditor.realtime_editor.messaging.RoomPresenceTracker;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.service.ChatService;
import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two complete relay "instances" (broker, presence tracker, Yjs + chat handlers and the real
 * {@link RedisConfig} listener container, each with its own Redis connection) talking through
 * a real Redis. Proves cross-instance delivery, no self-echo, viewer filtering, single
 * persistence of chat, the cluster-wide seeding decision, and the resync broadcast.
 * <p>
 * Needs Redis on localhost:6379 (as in docker-compose and CI); skipped otherwise.
 */
class CrossInstanceFanoutIntegrationTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String SECRET = "test-secret-key-for-unit-tests-minimum-32-characters-long";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final JwtService jwtService = new JwtService(SECRET, 3_600_000L);
    private final RoomService roomService = mock(RoomService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private Instance a;
    private Instance b;
    private String room;

    @BeforeAll
    static void requireRedis() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(REDIS_HOST, REDIS_PORT), 1000);
        } catch (Exception e) {
            assumeTrue(false, "Redis not available on " + REDIS_HOST + ":" + REDIS_PORT + " - skipping");
        }
    }

    @BeforeEach
    void startInstances() throws Exception {
        room = "it-" + UUID.randomUUID();
        when(roomService.getRole(anyString(), anyString())).thenReturn(Role.EDITOR);
        when(roomService.getRole(anyString(), org.mockito.ArgumentMatchers.eq("vera"))).thenReturn(Role.VIEWER);
        a = new Instance();
        b = new Instance();
    }

    @AfterEach
    void stopInstances() throws Exception {
        if (a != null) a.close();
        if (b != null) b.close();
    }

    // ── Seeding decision ──────────────────────────

    @Test
    @DisplayName("Only the first peer across ALL instances is told to seed")
    void seeding_shouldBeDecidedClusterWide() throws Exception {
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        FakeWebSocketSession bob = b.connectYjs("bob", room);

        assertThat(alice.binaryFramesOfType(4)).containsExactly(new byte[]{4, 1});
        assertThat(bob.binaryFramesOfType(4)).containsExactly(new byte[]{4, 0});
    }

    @Test
    @DisplayName("After the last peer on one instance leaves, a newcomer elsewhere seeds again")
    void seeding_shouldResetWhenRoomEmpties() throws Exception {
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        a.yjs.afterConnectionClosed(alice, CloseStatus.NORMAL);

        FakeWebSocketSession bob = b.connectYjs("bob", room);

        assertThat(bob.binaryFramesOfType(4)).containsExactly(new byte[]{4, 1});
    }

    // ── Yjs fanout ────────────────────────────────

    @Test
    @DisplayName("Edits cross instances in both directions, exactly once, with no echo to the sender")
    void edits_shouldFanOutBothWays() throws Exception {
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        FakeWebSocketSession bob = b.connectYjs("bob", room);
        alice.clearSent();
        bob.clearSent();

        a.yjs.handleMessage(alice, new BinaryMessage(new byte[]{1, 10, 20}));
        await(() -> bob.binaryFramesOfType(1).size() == 1);
        b.yjs.handleMessage(bob, new BinaryMessage(new byte[]{1, 30, 40}));
        await(() -> alice.binaryFramesOfType(1).size() == 1);

        // A marker frame published after both edits: once it arrives, anything else would have too.
        a.yjs.handleMessage(alice, new BinaryMessage(new byte[]{2, 99}));
        await(() -> bob.binaryFramesOfType(2).size() == 1);

        assertThat(bob.binaryFramesOfType(1)).containsExactly(new byte[]{1, 10, 20});
        assertThat(alice.binaryFramesOfType(1)).containsExactly(new byte[]{1, 30, 40});
        assertThat(alice.binaryFramesOfType(2)).isEmpty(); // no self-echo
    }

    @Test
    @DisplayName("A viewer's edit is dropped at its own instance and never reaches the other")
    void viewerEdits_shouldNotFanOut() throws Exception {
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        FakeWebSocketSession vera = b.connectYjs("vera", room);
        alice.clearSent();

        b.yjs.handleMessage(vera, new BinaryMessage(new byte[]{1, 66}));   // edit: dropped
        b.yjs.handleMessage(vera, new BinaryMessage(new byte[]{2, 77}));   // awareness: allowed
        await(() -> alice.binaryFramesOfType(2).size() == 1);

        assertThat(alice.binaryFramesOfType(1)).isEmpty();
    }

    @Test
    @DisplayName("Frames stay inside their room across instances")
    void frames_shouldNotLeakAcrossRooms() throws Exception {
        String otherRoom = room + "-other";
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        FakeWebSocketSession bobElsewhere = b.connectYjs("bob", otherRoom);
        FakeWebSocketSession carol = b.connectYjs("carol", room);
        bobElsewhere.clearSent();

        a.yjs.handleMessage(alice, new BinaryMessage(new byte[]{1, 5}));
        await(() -> carol.binaryFramesOfType(1).size() == 1);

        assertThat(bobElsewhere.binaryFrames()).isEmpty();
    }

    @Test
    @DisplayName("requestFullResync on one instance asks every client on every instance for its full state")
    void resync_shouldReachAllInstances() throws Exception {
        FakeWebSocketSession alice = a.connectYjs("alice", room);
        FakeWebSocketSession bob = b.connectYjs("bob", room);
        alice.clearSent();
        bob.clearSent();

        a.yjs.requestFullResync();

        await(() -> bob.binaryFramesOfType(0).size() == 1);
        assertThat(alice.binaryFramesOfType(0)).containsExactly(new byte[]{0, 0});
        assertThat(bob.binaryFramesOfType(0)).containsExactly(new byte[]{0, 0});
    }

    // ── Chat fanout ───────────────────────────────

    @Test
    @DisplayName("Chat is persisted once, on the receiving instance, and delivered on every instance")
    void chat_shouldPersistOnceAndFanOut() throws Exception {
        FakeWebSocketSession aliceChat = a.connectChat("alice", room);
        FakeWebSocketSession bobChat = b.connectChat("bob", room);
        ChatMessageResponse saved = new ChatMessageResponse("alice", "hello 8081", Instant.parse("2026-01-01T10:00:00Z"));
        when(a.chatService.saveMessage(room, "alice", "hello 8081")).thenReturn(saved);

        a.chat.handleMessage(aliceChat, new TextMessage("{\"content\":\"hello 8081\"}"));

        String expected = objectMapper.writeValueAsString(saved);
        await(() -> bobChat.textMessages().size() == 1);
        assertThat(bobChat.textMessages()).containsExactly(expected);
        assertThat(aliceChat.textMessages()).containsExactly(expected);
        verify(a.chatService, times(1)).saveMessage(room, "alice", "hello 8081");
        verify(b.chatService, never()).saveMessage(anyString(), anyString(), anyString());
    }

    // ── Helpers ───────────────────────────────────

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            Thread.sleep(10);
        }
    }

    /** One app instance: its own Redis connection, instance id, broker, tracker and handlers. */
    private final class Instance implements AutoCloseable {
        final ChatService chatService = mock(ChatService.class);
        final LettuceConnectionFactory factory;
        final RedisRoomBroker broker;
        final RoomPresenceTracker tracker;
        final YjsRelayWebSocketHandler yjs;
        final ChatWebSocketHandler chat;
        final RedisConfig config = new RedisConfig();
        final RedisMessageListenerContainer container;

        Instance() throws Exception {
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT));
            factory.afterPropertiesSet();
            factory.start();

            String instanceId = config.instanceId();
            broker = new RedisRoomBroker(factory, instanceId);
            tracker = new RoomPresenceTracker(new StringRedisTemplate(factory), instanceId);
            yjs = new YjsRelayWebSocketHandler(jwtService, roomService, objectMapper, broker, tracker);
            chat = new ChatWebSocketHandler(jwtService, chatService, objectMapper, broker);
            yjs.registerWithBroker();
            chat.registerWithBroker();

            CountDownLatch subscribed = new CountDownLatch(1);
            broker.onReconnect(subscribed::countDown); // fires once the pattern subscription is live
            container = config.redisMessageListenerContainer(factory, broker);
            container.afterPropertiesSet();
            container.start();
            if (!subscribed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Pub/sub subscription not established");
            }
        }

        FakeWebSocketSession connectYjs(String username, String roomId) throws Exception {
            FakeWebSocketSession s = FakeWebSocketSession.forRoom("/yjs", roomId, jwtService.generateToken(username));
            yjs.afterConnectionEstablished(s);
            return s;
        }

        FakeWebSocketSession connectChat(String username, String roomId) throws Exception {
            FakeWebSocketSession s = FakeWebSocketSession.forRoom("/ws/chat", roomId, jwtService.generateToken(username));
            chat.afterConnectionEstablished(s);
            return s;
        }

        @Override
        public void close() throws Exception {
            container.stop();
            container.destroy();
            config.destroy();
            tracker.shutdown();
            broker.shutdown();
            factory.destroy();
        }
    }
}