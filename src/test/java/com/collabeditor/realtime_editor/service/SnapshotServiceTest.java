package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.SaveSnapshotRequest;
import com.collabeditor.realtime_editor.dto.response.SnapshotResponse;
import com.collabeditor.realtime_editor.model.CodeSnapshot;
import com.collabeditor.realtime_editor.repository.CodeSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    @Mock
    private CodeSnapshotRepository snapshotRepository;

    @InjectMocks
    private SnapshotService snapshotService;

    @Test
    @DisplayName("Should save a snapshot and return response")
    void saveSnapshot_shouldSaveAndReturnResponse() {
        SaveSnapshotRequest request = new SaveSnapshotRequest();
        request.setRoomId("room-1");
        request.setCode("console.log('hello');");
        request.setLanguage("javascript");

        CodeSnapshot saved = new CodeSnapshot("room-1", "console.log('hello');", "javascript", "alice");
        saved.setId("snap-id-123");

        when(snapshotRepository.save(any(CodeSnapshot.class))).thenReturn(saved);
        when(snapshotRepository.findByRoomIdOrderByTimestampDesc("room-1"))
                .thenReturn(List.of(saved));

        SnapshotResponse response = snapshotService.saveSnapshot(request, "alice");

        assertNotNull(response);
        assertEquals("snap-id-123", response.getId());
        assertEquals("room-1", response.getRoomId());
        assertEquals("console.log('hello');", response.getCode());
        assertEquals("javascript", response.getLanguage());
        assertEquals("alice", response.getSavedBy());
        assertNotNull(response.getTimestamp());

        verify(snapshotRepository).save(any(CodeSnapshot.class));
    }

    @Test
    @DisplayName("Should return snapshots for a room ordered by timestamp desc")
    void getSnapshotsByRoom_shouldReturnListOrderedByTimestamp() {
        CodeSnapshot snap1 = new CodeSnapshot("room-1", "code v2", "python", "alice");
        snap1.setId("id-1");
        CodeSnapshot snap2 = new CodeSnapshot("room-1", "code v1", "python", "bob");
        snap2.setId("id-2");

        when(snapshotRepository.findByRoomIdOrderByTimestampDesc("room-1"))
                .thenReturn(List.of(snap1, snap2));

        List<SnapshotResponse> responses = snapshotService.getSnapshotsByRoom("room-1");

        assertEquals(2, responses.size());
        assertEquals("id-1", responses.get(0).getId());
        assertEquals("id-2", responses.get(1).getId());
    }

    @Test
    @DisplayName("Should return empty list when room has no snapshots")
    void getSnapshotsByRoom_shouldReturnEmptyListWhenNone() {
        when(snapshotRepository.findByRoomIdOrderByTimestampDesc("empty-room"))
                .thenReturn(List.of());

        List<SnapshotResponse> responses = snapshotService.getSnapshotsByRoom("empty-room");

        assertTrue(responses.isEmpty());
    }

    @Test
    @DisplayName("Should return latest snapshot for a room")
    void getLatestSnapshot_shouldReturnMostRecent() {
        CodeSnapshot latest = new CodeSnapshot("room-1", "latest code", "cpp", "alice");
        latest.setId("latest-id");

        when(snapshotRepository.findFirstByRoomIdOrderByTimestampDesc("room-1"))
                .thenReturn(Optional.of(latest));

        Optional<SnapshotResponse> result = snapshotService.getLatestSnapshot("room-1");

        assertTrue(result.isPresent());
        assertEquals("latest-id", result.get().getId());
        assertEquals("latest code", result.get().getCode());
    }

    @Test
    @DisplayName("Should return empty when no latest snapshot exists")
    void getLatestSnapshot_shouldReturnEmptyWhenNone() {
        when(snapshotRepository.findFirstByRoomIdOrderByTimestampDesc("empty-room"))
                .thenReturn(Optional.empty());

        Optional<SnapshotResponse> result = snapshotService.getLatestSnapshot("empty-room");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return snapshot by ID")
    void getSnapshotById_shouldReturnWhenFound() {
        CodeSnapshot snapshot = new CodeSnapshot("room-1", "some code", "java", "alice");
        snapshot.setId("abc-123");

        when(snapshotRepository.findById("abc-123")).thenReturn(Optional.of(snapshot));

        Optional<SnapshotResponse> result = snapshotService.getSnapshotById("abc-123");

        assertTrue(result.isPresent());
        assertEquals("abc-123", result.get().getId());
        assertEquals("some code", result.get().getCode());
    }

    @Test
    @DisplayName("Should return empty when snapshot ID not found")
    void getSnapshotById_shouldReturnEmptyWhenNotFound() {
        when(snapshotRepository.findById("nonexistent")).thenReturn(Optional.empty());

        Optional<SnapshotResponse> result = snapshotService.getSnapshotById("nonexistent");

        assertTrue(result.isEmpty());
    }
}
