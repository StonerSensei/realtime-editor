package com.collabeditor.realtime_editor.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link WebSocketSession} for handler tests: records every message sent to it and
 * how it was closed. Thread-safe so it can be used with pub/sub delivery threads.
 */
public class FakeWebSocketSession implements WebSocketSession {

    private final String id = UUID.randomUUID().toString();
    private final URI uri;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<WebSocketMessage<?>> sent = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;
    private volatile CloseStatus closeStatus;

    public FakeWebSocketSession(String path) {
        this.uri = URI.create("ws://localhost" + path);
    }

    /** e.g. {@code FakeWebSocketSession.forRoom("/yjs", "room1", "jwt")} */
    public static FakeWebSocketSession forRoom(String basePath, String roomId, String token) {
        return new FakeWebSocketSession(basePath + "/" + roomId + (token != null ? "?token=" + token : ""));
    }

    /** Binary frames received, as byte arrays, in order. */
    public List<byte[]> binaryFrames() {
        List<byte[]> frames = new ArrayList<>();
        for (WebSocketMessage<?> m : sent) {
            if (m instanceof BinaryMessage b) {
                ByteBuffer buf = b.getPayload().duplicate();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                frames.add(data);
            }
        }
        return frames;
    }

    /** Binary frames of one protocol type (first byte), in order. */
    public List<byte[]> binaryFramesOfType(int type) {
        return binaryFrames().stream().filter(f -> f.length > 0 && f[0] == type).toList();
    }

    /** Text messages received, in order. */
    public List<String> textMessages() {
        return sent.stream().filter(m -> m instanceof TextMessage)
                .map(m -> ((TextMessage) m).getPayload()).toList();
    }

    public CloseStatus getCloseStatus() {
        return closeStatus;
    }

    public void clearSent() {
        sent.clear();
    }

    @Override public String getId() { return id; }
    @Override public URI getUri() { return uri; }
    @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Principal getPrincipal() { return null; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public InetSocketAddress getRemoteAddress() { return null; }
    @Override public String getAcceptedProtocol() { return null; }
    @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
    @Override public int getTextMessageSizeLimit() { return Integer.MAX_VALUE; }
    @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
    @Override public int getBinaryMessageSizeLimit() { return Integer.MAX_VALUE; }
    @Override public List<WebSocketExtension> getExtensions() { return Collections.emptyList(); }
    @Override public boolean isOpen() { return open; }

    @Override
    public void sendMessage(WebSocketMessage<?> message) {
        if (!open) throw new IllegalStateException("session closed");
        sent.add(message);
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    @Override
    public void close(CloseStatus status) {
        open = false;
        closeStatus = status;
    }
}