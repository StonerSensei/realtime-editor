package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.response.MemberDto;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.exception.ForbiddenActionException;
import com.collabeditor.realtime_editor.exception.RoomAlreadyExistsException;
import com.collabeditor.realtime_editor.exception.RoomNotFoundException;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.model.Room;
import com.collabeditor.realtime_editor.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;

    public RoomResponse createRoom(CreateRoomRequest request, String owner) {
        String roomId = request.getRoomId();

        if (roomRepository.existsByRoomId(roomId)) {
            throw new RoomAlreadyExistsException(roomId);
        }

        Room room = new Room(roomId, request.getLanguage(), owner);
        Room saved = roomRepository.save(room);

        log.info("Room created: {} by user: {}", roomId, owner);
        return toResponse(saved, owner, "Room created successfully");
    }

    /** Joins the room, adding the user as a member with the default role if not already present. */
    public RoomResponse joinRoom(String roomId, String username) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        boolean changed = ensureOwnerMembership(room);

        if (!room.getMembers().containsKey(username)) {
            Role role = room.getDefaultRole() != null ? room.getDefaultRole() : Role.EDITOR;
            room.getMembers().put(username, role);
            changed = true;
            log.info("User {} joined room {} as {}", username, roomId, role);
        }

        if (changed) {
            room = roomRepository.save(room);
        }
        return toResponse(room, username, "Joined room successfully");
    }

    public RoomResponse getRoomDetails(String roomId, String username) {
        Room room = roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        if (ensureOwnerMembership(room)) {
            room = roomRepository.save(room);
        }
        return toResponse(room, username, null);
    }

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
        roomRepository.save(room);
        log.info("User {} kicked from room {} by {}", targetUser, roomId, actor);
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
                .createdAt(room.getCreatedAt())
                .message(message)
                .build();
    }
}
