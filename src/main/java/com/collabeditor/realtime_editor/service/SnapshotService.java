package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.SaveSnapshotRequest;
import com.collabeditor.realtime_editor.dto.response.SnapshotResponse;
import com.collabeditor.realtime_editor.model.CodeSnapshot;
import com.collabeditor.realtime_editor.repository.CodeSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    /** Maximum number of snapshots retained per room; older ones are pruned. */
    private static final int MAX_SNAPSHOTS_PER_ROOM = 50;

    private final CodeSnapshotRepository snapshotRepository;

    public SnapshotResponse saveSnapshot(SaveSnapshotRequest request, String savedBy) {
        CodeSnapshot snapshot = new CodeSnapshot(
                request.getRoomId(),
                request.getCode(),
                request.getLanguage(),
                savedBy
        );
        snapshot.setFiles(request.getFiles());

        CodeSnapshot saved = snapshotRepository.save(snapshot);
        pruneOldSnapshots(request.getRoomId());
        log.debug("Snapshot saved for room {} by {}", request.getRoomId(), savedBy);

        return toResponse(saved);
    }

    public List<SnapshotResponse> getSnapshotsByRoom(String roomId) {
        return snapshotRepository.findByRoomIdOrderByTimestampDesc(roomId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<SnapshotResponse> getLatestSnapshot(String roomId) {
        return snapshotRepository.findFirstByRoomIdOrderByTimestampDesc(roomId)
                .map(this::toResponse);
    }

    public Optional<SnapshotResponse> getSnapshotById(String id) {
        return snapshotRepository.findById(id)
                .map(this::toResponse);
    }

    /** Keeps only the newest {@link #MAX_SNAPSHOTS_PER_ROOM} snapshots for a room. */
    private void pruneOldSnapshots(String roomId) {
        List<CodeSnapshot> snapshots = snapshotRepository.findByRoomIdOrderByTimestampDesc(roomId);
        if (snapshots.size() > MAX_SNAPSHOTS_PER_ROOM) {
            List<CodeSnapshot> toDelete = snapshots.subList(MAX_SNAPSHOTS_PER_ROOM, snapshots.size());
            snapshotRepository.deleteAll(toDelete);
            log.debug("Pruned {} old snapshots for room {}", toDelete.size(), roomId);
        }
    }

    private SnapshotResponse toResponse(CodeSnapshot snapshot) {
        return SnapshotResponse.builder()
                .id(snapshot.getId())
                .roomId(snapshot.getRoomId())
                .code(snapshot.getCode())
                .files(snapshot.getFiles())
                .language(snapshot.getLanguage())
                .savedBy(snapshot.getSavedBy())
                .timestamp(snapshot.getTimestamp())
                .build();
    }
}
