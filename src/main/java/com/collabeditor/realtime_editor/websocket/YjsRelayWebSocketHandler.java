package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Role-aware binary relay for Yjs CRDT collaboration.
 * <p>
 * Broadcasts every binary frame from one peer to the other peers in the same room.
 * Conflict resolution happens client-side via Yjs. Authorization is enforced here:
 * <ul>
 *   <li>The connection is authenticated via a JWT passed as {@code ?token=...}.</li>
 *   <li>{@code VIEWER} document-mutating frames are dropped (server-side read-only).</li>
 * </ul>
 * The server also emits two control frames the relay itself generates:
 * <ul>
 *   <li>{@code PRESENCE} - tells a new peer whether it is first (seeding decision).</li>
 *   <li>{@code ROSTER}   - authoritative list of connected usernames, broadcast on
 *       every join/leave so clients show an accurate "online" count and presence.</li>
 * </ul>
 * Kicked users are closed with a distinct close code so their client can redirect
 * instead of attempting to reconnect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YjsRelayWebSocketHandler extends BinaryWebSocketHandler {

    /** Wire protocol message types (must match collab.js). */
    private static final byte TYPE_SYNC_UPDATE = 1;
    private static final byte TYPE_PRESENCE = 4;
    private static final byte TYPE_ROSTER = 5;

    /** Custom WebSocket close code signalling the user was removed from the room. */
    public static final int CLOSE_CODE_KICKED = 4001;

    private final JwtService jwtService;
    private final RoomService roomService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Role> sessionRoles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = getRoomId(session);
        String token = getQueryParam(session, "token");

        if (token == null || !jwtService.isTokenValid(token)) {
            log.warn("Rejecting Yjs connection to room '{}': invalid or missing token", roomId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String username = jwtService.extractUsername(token);
        Role role = roomService.getRole(roomId, username);
        if (role == null) {
            role = Role.VIEWER; // Not a member: safest default
        }

        sessionUsers.put(session.getId(), username);
        sessionRoles.put(session.getId(), role);

        Set<WebSocketSession> peers = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        boolean first;
        synchronized (peers) {
            first = peers.isEmpty();
            peers.add(session);
        }

        // Tell the new peer whether it is the first in the room (seeding decision).
        session.sendMessage(new BinaryMessage(new byte[]{TYPE_PRESENCE, (byte) (first ? 1 : 0)}));

        // Broadcast the updated roster to everyone (including the newcomer).
        broadcastRoster(roomId);

        log.info("Yjs peer joined room '{}' as {} (user={}, first={}, peers={})",
                roomId, role, username, first, peers.size());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String roomId = getRoomId(session);

        // Server-side read-only enforcement: drop document mutations from viewers.
        Role role = sessionRoles.get(session.getId());
        if (role == Role.VIEWER && isSyncUpdate(message)) {
            log.debug("Dropped edit from viewer {} in room {}", sessionUsers.get(session.getId()), roomId);
            return;
        }

        Set<WebSocketSession> peers = rooms.getOrDefault(roomId, Set.of());
        for (WebSocketSession peer : peers) {
            if (peer.isOpen() && !peer.getId().equals(session.getId())) {
                try {
                    peer.sendMessage(message);
                } catch (Exception e) {
                    log.warn("Failed to relay Yjs frame to {}: {}", peer.getId(), e.getMessage());
                }
            }
        }
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
        sessionRoles.remove(session.getId());
        sessionUsers.remove(session.getId());

        // Let the remaining peers know the roster shrank.
        broadcastRoster(roomId);
        log.info("Yjs peer left room '{}' (status={})", roomId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Yjs transport error (session {}): {}", session.getId(), exception.getMessage());
    }

    /** Force-closes any live sessions for a user in a room (used when they are kicked). */
    public void disconnectUser(String roomId, String username) {
        Set<WebSocketSession> peers = rooms.get(roomId);
        if (peers == null) return;
        for (WebSocketSession session : peers) {
            if (username.equals(sessionUsers.get(session.getId()))) {
                try {
                    session.close(new CloseStatus(CLOSE_CODE_KICKED, "Removed from room"));
                    log.info("Disconnected kicked user {} from room {}", username, roomId);
                } catch (Exception e) {
                    log.warn("Failed to disconnect {} from room {}: {}", username, roomId, e.getMessage());
                }
            }
        }
    }

    /** Sends the authoritative list of connected usernames to every peer in the room. */
    private void broadcastRoster(String roomId) {
        Set<WebSocketSession> peers = rooms.get(roomId);
        if (peers == null || peers.isEmpty()) return;

        List<String> usernames = peers.stream()
                .filter(WebSocketSession::isOpen)
                .map(s -> sessionUsers.get(s.getId()))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        try {
            byte[] json = objectMapper.writeValueAsBytes(usernames);
            byte[] frame = new byte[1 + json.length];
            frame[0] = TYPE_ROSTER;
            System.arraycopy(json, 0, frame, 1, json.length);
            BinaryMessage message = new BinaryMessage(frame);

            for (WebSocketSession peer : peers) {
                if (peer.isOpen()) {
                    try {
                        peer.sendMessage(message);
                    } catch (Exception e) {
                        log.warn("Failed to send roster to {}: {}", peer.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build roster for room {}: {}", roomId, e.getMessage());
        }
    }

    private boolean isSyncUpdate(BinaryMessage message) {
        var buffer = message.getPayload();
        return buffer.remaining() > 0 && buffer.get(buffer.position()) == TYPE_SYNC_UPDATE;
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
