package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.request.ChangeRoleRequest;
import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.request.InviteRequest;
import com.collabeditor.realtime_editor.dto.request.JoinRoomRequest;
import com.collabeditor.realtime_editor.dto.response.InvitationResponse;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.service.RoomService;
import com.collabeditor.realtime_editor.websocket.YjsRelayWebSocketHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Tag(name = "Rooms", description = "Create/join rooms and manage member roles (owner only)")
public class RoomController {

    private final RoomService roomService;
    private final YjsRelayWebSocketHandler yjsRelayWebSocketHandler;

    @PostMapping("/host")
    public ResponseEntity<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request,
                                                   Authentication authentication) {
        String username = authentication.getName();
        log.info("Request to create room: {} by user: {}", request.getRoomId(), username);
        RoomResponse response = roomService.createRoom(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Join a room with a join code, an invitation, or as an existing member")
    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomResponse> joinRoom(@PathVariable String roomId,
                                                 @RequestBody(required = false) JoinRoomRequest request,
                                                 Authentication authentication) {
        String username = authentication.getName();
        String joinCode = request != null ? request.getJoinCode() : null;
        log.info("User {} requesting to join room: {}", username, roomId);
        RoomResponse response = roomService.joinRoom(roomId, username, joinCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable String roomId,
                                                Authentication authentication) {
        RoomResponse response = roomService.getRoomDetails(roomId, authentication.getName());
        return ResponseEntity.ok(response);
    }

    // ── Invitations ───────────────────────────────

    @Operation(summary = "Invite a user to the room (owner only)")
    @PostMapping("/{roomId}/invite")
    public ResponseEntity<Void> invite(@PathVariable String roomId,
                                       @Valid @RequestBody InviteRequest request,
                                       Authentication authentication) {
        roomService.invite(roomId, authentication.getName(), request.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Revoke a pending invitation (owner only)")
    @DeleteMapping("/{roomId}/invite/{username}")
    public ResponseEntity<Void> revokeInvite(@PathVariable String roomId,
                                              @PathVariable String username,
                                              Authentication authentication) {
        roomService.revokeInvite(roomId, authentication.getName(), username);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List rooms the caller has been invited to")
    @GetMapping("/invitations")
    public ResponseEntity<List<InvitationResponse>> getInvitations(Authentication authentication) {
        return ResponseEntity.ok(roomService.getInvitations(authentication.getName()));
    }

    // ── Join code management ──────────────────────

    @Operation(summary = "Regenerate the room's join code (owner only; old code stops working)")
    @PostMapping("/{roomId}/regenerate-code")
    public ResponseEntity<Map<String, String>> regenerateCode(@PathVariable String roomId,
                                                              Authentication authentication) {
        String newCode = roomService.regenerateJoinCode(roomId, authentication.getName());
        return ResponseEntity.ok(Map.of("joinCode", newCode));
    }

    // ── Role management ───────────────────────────

    @PutMapping("/{roomId}/members/{username}/role")
    public ResponseEntity<RoomResponse> changeRole(@PathVariable String roomId,
                                                   @PathVariable String username,
                                                   @Valid @RequestBody ChangeRoleRequest request,
                                                   Authentication authentication) {
        RoomResponse response = roomService.changeRole(roomId, authentication.getName(), username, request.getRole());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{roomId}/members/{username}")
    public ResponseEntity<Void> kickMember(@PathVariable String roomId,
                                           @PathVariable String username,
                                           Authentication authentication) {
        roomService.kickMember(roomId, authentication.getName(), username);
        yjsRelayWebSocketHandler.disconnectUser(roomId, username);
        return ResponseEntity.noContent().build();
    }

    // ── User search (for invite UI) ───────────────

    @Operation(summary = "Search users by username (for the invite typeahead)")
    @GetMapping("/users/search")
    public ResponseEntity<List<String>> searchUsers(@RequestParam String q) {
        if (q == null || q.isBlank() || q.length() < 2) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(roomService.searchUsers(q));
    }
}