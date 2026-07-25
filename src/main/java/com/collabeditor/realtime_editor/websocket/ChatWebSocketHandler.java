package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.service.ChatService;
import com.collabeditor.realtime_editor.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time chat relay. Each incoming message is authenticated (JWT via {@code ?token=}),
 * persisted, and broadcast to every connected peer in the room (including the sender,
 * so all clients render server-confirmed messages with a consistent timestamp).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = getRoomId(session);
        String token = getQueryParam(session, "token");

        if (token == null || !jwtService.isTokenValid(token)) {
            log.warn("Rejecting chat connection to room '{}': invalid token", roomId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        sessionUsers.put(session.getId(), jwtService.extractUsername(token));
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("Chat connected: {} in room {}", sessionUsers.get(session.getId()), roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String roomId = getRoomId(session);
        String username = sessionUsers.get(session.getId());
        if (username == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Parse { "content": "..." }
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String content = payload.get("content") != null ? payload.get("content").toString() : "";
        if (content.isBlank()) return;

        ChatMessageResponse saved = chatService.saveMessage(roomId, username, content);
        String outbound = objectMapper.writeValueAsString(saved);

        broadcast(roomId, outbound);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = getRoomId(session);
        Set<WebSocketSession> peers = rooms.get(roomId);
        if (peers != null) {
            peers.remove(session);
            if (peers.isEmpty()) {
                rooms.remove(roomId);
            }
        }
        sessionUsers.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Chat transport error (session {}): {}", session.getId(), exception.getMessage());
    }

    private void broadcast(String roomId, String message) {
        Set<WebSocketSession> peers = rooms.getOrDefault(roomId, Set.of());
        for (WebSocketSession peer : peers) {
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
