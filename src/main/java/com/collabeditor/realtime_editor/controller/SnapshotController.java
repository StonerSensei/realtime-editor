package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.request.SaveSnapshotRequest;
import com.collabeditor.realtime_editor.dto.response.SnapshotResponse;
import com.collabeditor.realtime_editor.service.RoomService;
import com.collabeditor.realtime_editor.service.SnapshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/snapshots")
@RequiredArgsConstructor
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final RoomService roomService;

    @PostMapping("/save")
    public ResponseEntity<SnapshotResponse> saveSnapshot(@Valid @RequestBody SaveSnapshotRequest request,
                                                         Authentication authentication) {
        requireMember(request.getRoomId(), authentication);
        log.debug("Saving snapshot for room: {}", request.getRoomId());
        SnapshotResponse response = snapshotService.saveSnapshot(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<List<SnapshotResponse>> getSnapshots(@PathVariable @NotBlank String roomId,
                                                               Authentication authentication) {
        requireMember(roomId, authentication);
        List<SnapshotResponse> snapshots = snapshotService.getSnapshotsByRoom(roomId);
        return ResponseEntity.ok(snapshots);
    }

    @GetMapping("/get/{id}")
    public ResponseEntity<SnapshotResponse> getSnapshotById(@PathVariable @NotBlank String id,
                                                            Authentication authentication) {
        return snapshotService.getSnapshotById(id)
                .map(snap -> {
                    requireMember(snap.getRoomId(), authentication);
                    return ResponseEntity.ok(snap);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/latest/{roomId}")
    public ResponseEntity<SnapshotResponse> getLatestSnapshot(@PathVariable @NotBlank String roomId,
                                                              Authentication authentication) {
        requireMember(roomId, authentication);
        return snapshotService.getLatestSnapshot(roomId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Throws 403 if the caller is not a member of the room.
     */
    private void requireMember(String roomId, Authentication authentication) {
        if (roomService.getRole(roomId, authentication.getName()) == null) {
            throw new com.collabeditor.realtime_editor.exception.ForbiddenActionException(
                    "You are not a member of this room");
        }
    }
}