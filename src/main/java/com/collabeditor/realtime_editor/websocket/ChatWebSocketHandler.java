package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.messaging.RedisRoomBroker;
import com.collabeditor.realtime_editor.service.ChatService;
import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time chat relay. Each incoming message is authenticated (JWT via {@code ?token=}),
 * persisted, and broadcast to every connected peer in the room (including the sender,
 * so all clients render server-confirmed messages with a consistent timestamp).
 * <p>
 * With several instances, the receiving instance persists the message once, delivers it
 * locally, then publishes it through {@link RedisRoomBroker}; other instances only deliver
 * it to their own sessions. Sessions are wrapped in {@link ConcurrentWebSocketSessionDecorator}
 * because local broadcasts and pub/sub deliveries can target one session concurrently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** Max time one send may block before the session is considered stuck and closed. */
    static final int SEND_TIME_LIMIT_MS = 10_000;

    /** Max bytes queued for a slow client before it is disconnected. */
    static final int SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;

    private final JwtService jwtService;
    private final ChatService chatService;
    private final RoomService roomService;
    private final ObjectMapper objectMapper;
    private final RedisRoomBroker broker;

    /** roomId -> local sessions (decorated). */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    /** sessionId -> decorated session, for sessions that passed authentication. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

    @PostConstruct
    void registerWithBroker() {
        broker.onChat(this::broadcast);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        String roomId = getRoomId(rawSession);
        String token = getQueryParam(rawSession, "token");

        if (token == null || !jwtService.isTokenValid(token)) {
            log.warn("Rejecting chat connection to room '{}': invalid token", roomId);
            rawSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        String username = jwtService.extractUsername(token);

        if (roomService.getRole(roomId, username) == null) {
            log.warn("Rejecting chat connection to room '{}': user '{}' is not a member", roomId, username);
            rawSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        sessions.put(session.getId(), session);
        sessionUsers.put(session.getId(), username);
        rooms.compute(roomId, (id, peers) -> {
            Set<WebSocketSession> set = peers != null ? peers : ConcurrentHashMap.newKeySet();
            set.add(session);
            return set;
        });
        log.debug("Chat connected: {} in room {}", sessionUsers.get(session.getId()), roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) throws Exception {
        String roomId = getRoomId(rawSession);
        String username = sessionUsers.get(rawSession.getId());
        if (username == null) {
            rawSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Parse { "content": "..." }
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String content = payload.get("content") != null ? payload.get("content").toString() : "";
        if (content.isBlank()) return;

        ChatMessageResponse saved = chatService.saveMessage(roomId, username, content);
        String outbound = objectMapper.writeValueAsString(saved);

        broadcast(roomId, outbound);
        broker.publishChat(roomId, outbound);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        WebSocketSession session = sessions.remove(rawSession.getId());
        sessionUsers.remove(rawSession.getId());
        if (session == null) {
            return; // was rejected at connect
        }
        rooms.computeIfPresent(getRoomId(rawSession), (id, peers) -> {
            peers.remove(session);
            return peers.isEmpty() ? null : peers;
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Chat transport error (session {}): {}", session.getId(), exception.getMessage());
    }

    /**
     * Delivers a chat message to every local session in the room. Used both for messages
     * received here and for messages published by other instances.
     */
    void broadcast(String roomId, String message) {
        for (WebSocketSession peer : rooms.getOrDefault(roomId, Set.of())) {
            if (peer.isOpen()) {
                try {
                    peer.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.warn("Failed to deliver chat message to {}: {}", peer.getId(), e.getMessage());
                }
            }
        }
    }

    private String getRoomId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return "default";
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String getQueryParam(WebSocketSession session, String key) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }
}