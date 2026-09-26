package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.response.InvitationResponse;
import com.collabeditor.realtime_editor.dto.response.MemberDto;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.exception.ForbiddenActionException;
import com.collabeditor.realtime_editor.exception.RoomAlreadyExistsException;
import com.collabeditor.realtime_editor.exception.RoomNotFoundException;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.model.Room;
import com.collabeditor.realtime_editor.repository.RoomRepository;
import com.collabeditor.realtime_editor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    // ── Create ────────────────────────────────────

    public RoomResponse createRoom(CreateRoomRequest request, String owner) {
        String roomId = request.getRoomId();

        if (roomRepository.existsByRoomId(roomId)) {
            throw new RoomAlreadyExistsException(roomId);
        }

        Room room = new Room(roomId, request.getLanguage(), owner, generateJoinCode());
        Room saved = roomRepository.save(room);

        log.info("Room created: {} by user: {} (code: {})", roomId, owner, saved.getJoinCode());
        return toResponse(saved, owner, "Room created successfully");
    }

    // ── Join ──────────────────────────────────────

    /**
     * Joins the room. The caller must be an existing member, hold a pending invitation,
     * or supply the correct join code.
     */
    public RoomResponse joinRoom(String roomId, String username, String joinCode) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        boolean changed = ensureOwnerMembership(room);

        if (room.getMembers().containsKey(username)) {
            // Already a member: let them back in without a code.
            if (changed) {
                room = roomRepository.save(room);
            }
            return toResponse(room, username, "Joined room successfully");
        }

        // Not yet a member: check they haven't been kicked, then check invitation or code.
        if (room.getKickedUsers() != null && room.getKickedUsers().contains(username)) {
            throw new ForbiddenActionException("You have been removed from this room");
        }

        boolean invited = room.getInvitedUsers() != null && room.getInvitedUsers().remove(username);
        if (!invited) {
            if (joinCode == null || joinCode.isBlank()) {
                throw new ForbiddenActionException("A join code is required to enter this room");
            }
            if (!joinCode.equalsIgnoreCase(room.getJoinCode())) {
                throw new ForbiddenActionException("Invalid join code");
            }
        }

        Role role = room.getDefaultRole() != null ? room.getDefaultRole() : Role.EDITOR;
        room.getMembers().put(username, role);
        log.info("User {} joined room {} as {} ({})", username, roomId, role,
                invited ? "invited" : "code");

        room = roomRepository.save(room);
        return toResponse(room, username, "Joined room successfully");
    }

    // ── Invitations ───────────────────────────────

    /** Owner invites a user. The user must exist. */
    public void invite(String roomId, String actor, String targetUsername) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        requireOwner(room, actor);

        if (!userRepository.existsByUsername(targetUsername)) {
            throw new ForbiddenActionException("User not found: " + targetUsername);
        }
        if (room.getMembers().containsKey(targetUsername)) {
            throw new ForbiddenActionException(targetUsername + " is already a member");
        }

        room.getInvitedUsers().add(targetUsername);
        roomRepository.save(room);
        log.info("User {} invited to room {} by {}", targetUsername, roomId, actor);
    }

    /** Owner revokes a pending invitation. */
    public void revokeInvite(String roomId, String actor, String targetUsername) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        requireOwner(room, actor);

        if (room.getInvitedUsers() == null || !room.getInvitedUsers().remove(targetUsername)) {
            throw new ForbiddenActionException("No pending invitation for " + targetUsername);
        }
        roomRepository.save(room);
        log.info("Invitation for {} to room {} revoked by {}", targetUsername, roomId, actor);
    }

    /** Returns rooms the user has been invited to (for the lobby). */
    public List<InvitationResponse> getInvitations(String username) {
        return roomRepository.findByInvitedUsersContaining(username).stream()
                .map(room -> InvitationResponse.builder()
                        .roomId(room.getRoomId())
                        .language(room.getLanguage())
                        .owner(room.getOwner())
                        .createdAt(room.getCreatedAt())
                        .build())
                .toList();
    }

    // ── Join code management ──────────────────────

    /** Owner regenerates the join code (old code stops working). */
    public String regenerateJoinCode(String roomId, String actor) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        requireOwner(room, actor);

        String newCode = generateJoinCode();
        room.setJoinCode(newCode);
        roomRepository.save(room);
        log.info("Join code for room {} regenerated by {}", roomId, actor);
        return newCode;
    }

    // ── Room details ──────────────────────────────

    public RoomResponse getRoomDetails(String roomId, String username) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        if (ensureOwnerMembership(room)) {
            room = roomRepository.save(room);
        }
        return toResponse(room, username, null);
    }

    // ── Role management ───────────────────────────

    public RoomResponse changeRole(String roomId, String actor, String targetUser, Role newRole) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        requireOwner(room, actor);

        if (targetUser.equals(room.getOwner())) {
            throw new ForbiddenActionException("The room owner's role cannot be changed");
        }
        if (!room.getMembers().containsKey(targetUser)) {
            throw new ForbiddenActionException("User is not a member of this room: " + targetUser);
        }
        if (newRole == Role.OWNER) {
            throw new ForbiddenActionException("Cannot assign OWNER role");
        }

        room.getMembers().put(targetUser, newRole);
        Room saved = roomRepository.save(room);
        log.info("Role of {} in room {} changed to {} by {}", targetUser, roomId, newRole, actor);

        return toResponse(saved, actor, "Role updated");
    }

    public void kickMember(String roomId, String actor, String targetUser) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        requireOwner(room, actor);

        if (targetUser.equals(room.getOwner())) {
            throw new ForbiddenActionException("The room owner cannot be removed");
        }

        room.getMembers().remove(targetUser);
        if (room.getKickedUsers() == null) {
            room.setKickedUsers(new java.util.HashSet<>());
        }
        room.getKickedUsers().add(targetUser);
        roomRepository.save(room);
        log.info("User {} kicked from room {} by {}", targetUser, roomId, actor);
    }

    /** Owner un-kicks a user so they can rejoin with the code or an invitation. */
    public void unkick(String roomId, String actor, String targetUser) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        requireOwner(room, actor);

        if (room.getKickedUsers() == null || !room.getKickedUsers().remove(targetUser)) {
            throw new ForbiddenActionException(targetUser + " is not on the kicked list");
        }
        roomRepository.save(room);
        log.info("User {} un-kicked from room {} by {}", targetUser, roomId, actor);
    }

    /** Returns the user's role in a room, or {@code null} if they are not a member. */
    public Role getRole(String roomId, String username) {
        return roomRepository.findByRoomId(roomId)
                .map(room -> room.getMembers().get(username))
                .orElse(null);
    }

    public boolean roomExists(String roomId) {
        return roomRepository.existsByRoomId(roomId);
    }

    // ── User search ───────────────────────────────

    /** Searches users by partial username (for the invite UI). */
    public List<String> searchUsers(String query) {
        return userRepository.findByUsernameContainingIgnoreCase(query).stream()
                .map(u -> u.getUsername())
                .limit(10)
                .toList();
    }

    // ── Internal ──────────────────────────────────

    /**
     * Self-heals rooms created before the roles feature existed: guarantees the
     * room's owner is present in the members map with the OWNER role. Returns
     * {@code true} if the room was modified.
     */
    private boolean ensureOwnerMembership(Room room) {
        if (room.getMembers() == null) {
            room.setMembers(new java.util.HashMap<>());
        }
        if (room.getOwner() != null && room.getMembers().get(room.getOwner()) != Role.OWNER) {
            room.getMembers().put(room.getOwner(), Role.OWNER);
            return true;
        }
        return false;
    }

    private void requireOwner(Room room, String actor) {
        if (!actor.equals(room.getOwner())) {
            throw new ForbiddenActionException("Only the room owner can perform this action");
        }
    }

    private RoomResponse toResponse(Room room, String username, String message) {
        List<MemberDto> members = room.getMembers().entrySet().stream()
                .map(e -> new MemberDto(e.getKey(), e.getValue()))
                .toList();

        return RoomResponse.builder()
                .roomId(room.getRoomId())
                .language(room.getLanguage())
                .owner(room.getOwner())
                .role(room.getMembers().get(username))
                .members(members)
                .joinCode(username.equals(room.getOwner()) ? room.getJoinCode() : null)
                .createdAt(room.getCreatedAt())
                .message(message)
                .build();
    }

    private String generateJoinCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}