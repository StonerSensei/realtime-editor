package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.messaging.RedisRoomBroker;
import com.collabeditor.realtime_editor.service.ChatService;
import com.collabeditor.realtime_editor.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.time.Instant;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-minimum-32-characters-long";
    private static final String ROOM = "room-1";

    @Mock
    private ChatService chatService;

    @Mock
    private RedisRoomBroker broker;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private JwtService jwtService;
    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3_600_000L);
        handler = new ChatWebSocketHandler(jwtService, chatService, objectMapper, broker);
    }

    private FakeWebSocketSession connect(String username) throws Exception {
        FakeWebSocketSession session = FakeWebSocketSession.forRoom("/ws/chat", ROOM, jwtService.generateToken(username));
        handler.afterConnectionEstablished(session);
        return session;
    }

    @Test
    @DisplayName("A message is persisted once, delivered to every local session (incl. sender), then published")
    void message_shouldPersistBroadcastAndPublish() throws Exception {
        FakeWebSocketSession alice = connect("alice");
        FakeWebSocketSession bob = connect("bob");
        ChatMessageResponse saved = new ChatMessageResponse("alice", "hi there", Instant.parse("2026-01-01T10:00:00Z"));
        when(chatService.saveMessage(ROOM, "alice", "hi there")).thenReturn(saved);

        handler.handleMessage(alice, new TextMessage("{\"content\":\"hi there\"}"));

        String expected = objectMapper.writeValueAsString(saved);
        assertThat(alice.textMessages()).containsExactly(expected);
        assertThat(bob.textMessages()).containsExactly(expected);
        verify(chatService).saveMessage(ROOM, "alice", "hi there");
        verify(broker).publishChat(ROOM, expected);
    }

    @Test
    @DisplayName("A message from another instance is delivered locally but never persisted or re-published")
    @SuppressWarnings("unchecked")
    void remoteMessage_shouldOnlyBeDelivered() throws Exception {
        ArgumentCaptor<BiConsumer<String, String>> receiver = ArgumentCaptor.forClass(BiConsumer.class);
        handler.registerWithBroker();
        verify(broker).onChat(receiver.capture());
        FakeWebSocketSession alice = connect("alice");

        String remote = "{\"username\":\"zoe\",\"content\":\"from 8081\",\"timestamp\":\"2026-01-01T10:00:00Z\"}";
        receiver.getValue().accept(ROOM, remote);
        receiver.getValue().accept("other-room", "{}");

        assertThat(alice.textMessages()).containsExactly(remote);
        verify(chatService, never()).saveMessage(anyString(), anyString(), anyString());
        verify(broker, never()).publishChat(anyString(), anyString());
    }

    @Test
    @DisplayName("Blank messages are ignored: nothing persisted, delivered or published")
    void blankMessage_shouldBeIgnored() throws Exception {
        FakeWebSocketSession alice = connect("alice");

        handler.handleMessage(alice, new TextMessage("{\"content\":\"   \"}"));

        assertThat(alice.textMessages()).isEmpty();
        verify(broker, never()).publishChat(anyString(), anyString());
    }

    @Test
    @DisplayName("Invalid token: connection closed with POLICY_VIOLATION")
    void invalidToken_shouldBeRejected() throws Exception {
        FakeWebSocketSession session = FakeWebSocketSession.forRoom("/ws/chat", ROOM, "bad");

        handler.afterConnectionEstablished(session);

        assertThat(session.getCloseStatus()).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("A closed session stops receiving messages")
    void closedSession_shouldStopReceiving() throws Exception {
        FakeWebSocketSession alice = connect("alice");
        FakeWebSocketSession bob = connect("bob");
        handler.afterConnectionClosed(bob, CloseStatus.NORMAL);

        handler.broadcast(ROOM, "{\"content\":\"x\"}");

        assertThat(alice.textMessages()).hasSize(1);
        assertThat(bob.textMessages()).isEmpty();
    }
}