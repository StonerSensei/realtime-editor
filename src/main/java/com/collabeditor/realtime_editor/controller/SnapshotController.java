package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.request.SaveSnapshotRequest;
import com.collabeditor.realtime_editor.dto.response.SnapshotResponse;
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

    @PostMapping("/save")
    public ResponseEntity<SnapshotResponse> saveSnapshot(@Valid @RequestBody SaveSnapshotRequest request,
                                                         Authentication authentication) {
        log.debug("Saving snapshot for room: {}", request.getRoomId());
        SnapshotResponse response = snapshotService.saveSnapshot(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<List<SnapshotResponse>> getSnapshots(@PathVariable @NotBlank String roomId) {
        List<SnapshotResponse> snapshots = snapshotService.getSnapshotsByRoom(roomId);
        return ResponseEntity.ok(snapshots);
    }

    @GetMapping("/get/{id}")
    public ResponseEntity<SnapshotResponse> getSnapshotById(@PathVariable @NotBlank String id) {
        return snapshotService.getSnapshotById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/latest/{roomId}")
    public ResponseEntity<SnapshotResponse> getLatestSnapshot(@PathVariable @NotBlank String roomId) {
        return snapshotService.getLatestSnapshot(roomId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
