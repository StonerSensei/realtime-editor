package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.request.ChangeRoleRequest;
import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.service.RoomService;
import com.collabeditor.realtime_editor.websocket.YjsRelayWebSocketHandler;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomResponse> joinRoom(@PathVariable String roomId,
                                                 Authentication authentication) {
        String username = authentication.getName();
        log.info("User {} requesting to join room: {}", username, roomId);
        RoomResponse response = roomService.joinRoom(roomId, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable String roomId,
                                                Authentication authentication) {
        RoomResponse response = roomService.getRoomDetails(roomId, authentication.getName());
        return ResponseEntity.ok(response);
    }

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
        // Force-disconnect the kicked user's live collaboration session
        yjsRelayWebSocketHandler.disconnectUser(roomId, username);
        return ResponseEntity.noContent().build();
    }
}
