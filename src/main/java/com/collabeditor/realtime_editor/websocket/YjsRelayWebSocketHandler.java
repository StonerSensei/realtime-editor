package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.messaging.RedisRoomBroker;
import com.collabeditor.realtime_editor.messaging.RoomPresenceTracker;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Role-aware binary relay for Yjs CRDT collaboration, fanned out across instances.
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
 *   <li>{@code ROSTER}   - list of usernames connected <i>to this instance</i>, broadcast on
 *       every local join/leave.</li>
 * </ul>
 * Kicked users are closed with a distinct close code so their client can redirect
 * instead of attempting to reconnect.
 *
 * <h2>Multiple instances</h2>
 * Frames are delivered to local peers immediately, then published through
 * {@link RedisRoomBroker} so other instances deliver them to their peers; frames arriving
 * from other instances go to every local peer via {@link #deliverToLocalRoom}. Viewer edits
 * are dropped before publishing, so filtering happens exactly once, on the sender's instance.
 * A peer is only told it is first if no other instance has peers in the room either
 * ({@link RoomPresenceTracker}). After a Redis outage, {@link #requestFullResync} makes every
 * client re-broadcast its full document so instances that diverged converge again.
 * <p>
 * Sessions are wrapped in {@link ConcurrentWebSocketSessionDecorator} because frames for one
 * session can now be sent from several threads at once (WebSocket request threads and the
 * pub/sub dispatch thread); a raw session rejects concurrent sends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YjsRelayWebSocketHandler extends BinaryWebSocketHandler {

    /** Wire protocol message types (must match collab.js). */
    private static final byte TYPE_SYNC_REQUEST = 0;
    private static final byte TYPE_SYNC_UPDATE = 1;
    private static final byte TYPE_PRESENCE = 4;
    private static final byte TYPE_ROSTER = 5;

    /**
     * SYNC_REQUEST carrying an empty Yjs state vector (a single varint 0). A client answers
     * it with its entire document as one SYNC_UPDATE, which the relay then fans out.
     */
    static final byte[] FULL_RESYNC_REQUEST = {TYPE_SYNC_REQUEST, 0};

    /** Custom WebSocket close code signalling the user was removed from the room. */
    public static final int CLOSE_CODE_KICKED = 4001;

    /** Max time one send may block before the session is considered stuck and closed. */
    static final int SEND_TIME_LIMIT_MS = 10_000;

    /** Max bytes queued for a slow client before it is disconnected (it will reconnect). */
    static final int SEND_BUFFER_LIMIT_BYTES = 8 * 1024 * 1024;

    private final JwtService jwtService;
    private final RoomService roomService;
    private final ObjectMapper objectMapper;
    private final RedisRoomBroker broker;
    private final RoomPresenceTracker presenceTracker;

    /** roomId -> local peers (decorated sessions). */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    /** sessionId -> decorated session, for sessions that passed authentication. */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Role> sessionRoles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();

    @PostConstruct
    void registerWithBroker() {
        broker.onYjs(this::deliverToLocalRoom);
        presenceTracker.trackActiveRooms(rooms::keySet);
        // On reconnect, restore presence first (a restarted Redis lost it), then resync documents.
        broker.onReconnect(presenceTracker::refreshNow);
        broker.onReconnect(this::requestFullResync);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        String roomId = getRoomId(rawSession);
        String token = getQueryParam(rawSession, "token");

        if (token == null || !jwtService.isTokenValid(token)) {
            log.warn("Rejecting Yjs connection to room '{}': invalid or missing token", roomId);
            rawSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String username = jwtService.extractUsername(token);
        Role role = roomService.getRole(roomId, username);
        if (role == null) {
            log.warn("Rejecting Yjs connection to room '{}': user '{}' is not a member", roomId, username);
            rawSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessions.put(session.getId(), session);
        sessionUsers.put(session.getId(), username);
        sessionRoles.put(session.getId(), role);

        // Atomic per room: a concurrent last-peer-leaves cannot orphan this set.
        boolean[] firstLocally = new boolean[1];
        rooms.compute(roomId, (id, peers) -> {
            Set<WebSocketSession> set = peers != null ? peers : ConcurrentHashMap.newKeySet();
            firstLocally[0] = set.isEmpty();
            set.add(session);
            return set;
        });

        // First on this instance: register the room cluster-wide and learn whether peers on
        // other instances already hold the document. Only a peer that is first everywhere seeds.
        boolean first = firstLocally[0] && !presenceTracker.markActive(roomId);

        // Tell the new peer whether it is the first in the room (seeding decision).
        send(session, new byte[]{TYPE_PRESENCE, (byte) (first ? 1 : 0)});

        // Broadcast the updated roster to everyone (including the newcomer).
        broadcastRoster(roomId);

        log.info("Yjs peer joined room '{}' as {} (user={}, first={}, localPeers={})",
                roomId, role, username, first, rooms.getOrDefault(roomId, Set.of()).size());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession rawSession, BinaryMessage message) {
        String sessionId = rawSession.getId();
        Role role = sessionRoles.get(sessionId);
        if (role == null) {
            return; // not an authenticated, registered session
        }

        String roomId = getRoomId(rawSession);
        byte[] data = toBytes(message.getPayload());
        if (data.length == 0) {
            return;
        }

        // Server-side read-only enforcement: drop document mutations from viewers.
        if (role == Role.VIEWER && data[0] == TYPE_SYNC_UPDATE) {
            log.debug("Dropped edit from viewer {} in room {}", sessionUsers.get(sessionId), roomId);
            return;
        }

        // Local peers first (lowest latency), then the other instances.
        for (WebSocketSession peer : rooms.getOrDefault(roomId, Set.of())) {
            if (!peer.getId().equals(sessionId)) {
                send(peer, data);
            }
        }
        broker.publishYjs(roomId, data);
    }

    /**
     * Delivers a frame received from another instance to every local peer in the room.
     * There is no local sender to skip: the frame originated elsewhere.
     */
    void deliverToLocalRoom(String roomId, byte[] data) {
        for (WebSocketSession peer : rooms.getOrDefault(roomId, Set.of())) {
            send(peer, data);
        }
    }

    /**
     * Asks every client in every local room, and via Redis in every other instance, to
     * re-broadcast its full document. Run when Redis becomes usable again after an outage:
     * frames exchanged meanwhile never crossed instances, and Yjs clients only sync on
     * connect, so without this the instances' copies would stay diverged. Merging full
     * states is idempotent, so extra runs are harmless.
     */
    void requestFullResync() {
        List<String> roomIds = List.copyOf(rooms.keySet());
        if (roomIds.isEmpty()) {
            return;
        }
        log.info("Requesting full Yjs resync for {} room(s)", roomIds.size());
        for (String roomId : roomIds) {
            deliverToLocalRoom(roomId, FULL_RESYNC_REQUEST);
            broker.publishYjs(roomId, FULL_RESYNC_REQUEST);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        String sessionId = rawSession.getId();
        WebSocketSession session = sessions.remove(sessionId);
        sessionRoles.remove(sessionId);
        sessionUsers.remove(sessionId);
        if (session == null) {
            return; // was rejected at connect; never joined a room
        }

        String roomId = getRoomId(rawSession);
        boolean[] roomEmptied = new boolean[1];
        rooms.computeIfPresent(roomId, (id, peers) -> {
            peers.remove(session);
            roomEmptied[0] = peers.isEmpty();
            return peers.isEmpty() ? null : peers;
        });

        if (roomEmptied[0] && !rooms.containsKey(roomId)) {
            // Last local peer gone. (If someone rejoins in between, the heartbeat re-registers.)
            presenceTracker.markInactive(roomId);
        }

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
        for (WebSocketSession session : rooms.getOrDefault(roomId, Set.of())) {
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

    /** Sends the list of usernames connected to this instance to every local peer in the room. */
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

        byte[] frame;
        try {
            byte[] json = objectMapper.writeValueAsBytes(usernames);
            frame = new byte[1 + json.length];
            frame[0] = TYPE_ROSTER;
            System.arraycopy(json, 0, frame, 1, json.length);
        } catch (Exception e) {
            log.warn("Failed to build roster for room {}: {}", roomId, e.getMessage());
            return;
        }

        for (WebSocketSession peer : peers) {
            send(peer, frame);
        }
    }

    /**
     * Sends one frame to one session. A fresh {@link BinaryMessage} is created per send so no
     * two sends share a ByteBuffer position. Failures are logged, never propagated.
     */
    private void send(WebSocketSession session, byte[] data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(data));
        } catch (Exception e) {
            log.warn("Failed to send Yjs frame to {}: {}", session.getId(), e.getMessage());
        }
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        return data;
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