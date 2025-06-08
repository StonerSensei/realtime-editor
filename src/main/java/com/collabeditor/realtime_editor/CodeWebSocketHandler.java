package com.collabeditor.realtime_editor;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CodeWebSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = getRoomId(session);
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        System.out.println("✅ User joined room: " + roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String roomId = getRoomId(session);

        System.out.println("📩 Message from " + session.getId() + " in room " + roomId + ": " + payload);

        for (WebSocketSession s : rooms.getOrDefault(roomId, Set.of())) {
            if (s.isOpen() && !s.getId().equals(session.getId())) {
                try {
                    s.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    System.err.println("⚠️ Failed to send message to " + s.getId() + ": " + e.getMessage());
                    s.close();
                    rooms.get(roomId).remove(s);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = getRoomId(session);
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                rooms.remove(roomId);
            }
        }
        System.out.println("❌ User left room: " + roomId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("❌ Transport error on session " + session.getId() + ": " + exception.getMessage());
    }

    private String getRoomId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return "default";
        String path = uri.toString();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
