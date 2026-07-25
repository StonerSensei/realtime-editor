package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.exception.ForbiddenActionException;
import com.collabeditor.realtime_editor.exception.RoomAlreadyExistsException;
import com.collabeditor.realtime_editor.exception.RoomNotFoundException;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.model.Room;
import com.collabeditor.realtime_editor.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private RoomService roomService;

    private CreateRoomRequest createRoomRequest;

    @BeforeEach
    void setUp() {
        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setRoomId("test-room-123");
        createRoomRequest.setLanguage("javascript");
    }

    @Test
    @DisplayName("Should create a room with the creator as OWNER")
    void createRoom_shouldSucceedWithValidRequest() {
        when(roomRepository.existsByRoomId("test-room-123")).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomResponse response = roomService.createRoom(createRoomRequest, "owner-user");

        assertNotNull(response);
        assertEquals("test-room-123", response.getRoomId());
        assertEquals("javascript", response.getLanguage());
        assertEquals("owner-user", response.getOwner());
        assertEquals(Role.OWNER, response.getRole());
        assertEquals("Room created successfully", response.getMessage());
        assertNotNull(response.getCreatedAt());

        verify(roomRepository).save(any(Room.class));
    }

    @Test
    @DisplayName("Should throw exception when room already exists")
    void createRoom_shouldThrowWhenRoomExists() {
        when(roomRepository.existsByRoomId("test-room-123")).thenReturn(true);

        assertThrows(RoomAlreadyExistsException.class,
                () -> roomService.createRoom(createRoomRequest, "owner-user"));

        verify(roomRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should join an existing room as EDITOR (default role)")
    void joinRoom_shouldSucceedWhenRoomExists() {
        Room room = new Room("test-room-123", "python", "someone");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomResponse response = roomService.joinRoom("test-room-123", "new-user");

        assertNotNull(response);
        assertEquals("test-room-123", response.getRoomId());
        assertEquals("python", response.getLanguage());
        assertEquals(Role.EDITOR, response.getRole());
        assertEquals("Joined room successfully", response.getMessage());
    }

    @Test
    @DisplayName("Should not re-add an existing member on join")
    void joinRoom_shouldNotDuplicateExistingMember() {
        Room room = new Room("test-room-123", "python", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        RoomResponse response = roomService.joinRoom("test-room-123", "owner-user");

        assertEquals(Role.OWNER, response.getRole());
        // Owner already a member, so no save should occur
        verify(roomRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw exception when joining non-existent room")
    void joinRoom_shouldThrowWhenRoomNotFound() {
        when(roomRepository.findByRoomId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(RoomNotFoundException.class,
                () -> roomService.joinRoom("nonexistent", "user"));
    }

    @Test
    @DisplayName("Owner should be able to change a member's role")
    void changeRole_shouldSucceedForOwner() {
        Room room = new Room("test-room-123", "python", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomResponse response = roomService.changeRole("test-room-123", "owner-user", "member-user", Role.VIEWER);

        assertEquals(Role.VIEWER, response.getMembers().stream()
                .filter(m -> m.getUsername().equals("member-user"))
                .findFirst().orElseThrow().getRole());
    }

    @Test
    @DisplayName("Non-owner should not be able to change roles")
    void changeRole_shouldThrowForNonOwner() {
        Room room = new Room("test-room-123", "python", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.changeRole("test-room-123", "member-user", "owner-user", Role.VIEWER));
    }

    @Test
    @DisplayName("Owner should be able to kick a member")
    void kickMember_shouldSucceedForOwner() {
        Room room = new Room("test-room-123", "python", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        roomService.kickMember("test-room-123", "owner-user", "member-user");

        assertFalse(room.getMembers().containsKey("member-user"));
    }

    @Test
    @DisplayName("Owner cannot be kicked")
    void kickMember_shouldThrowWhenKickingOwner() {
        Room room = new Room("test-room-123", "python", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.kickMember("test-room-123", "owner-user", "owner-user"));
    }

    @Test
    @DisplayName("Should return the user's role via getRole")
    void getRole_shouldReturnMemberRole() {
        Room room = new Room("test-room-123", "python", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertEquals(Role.OWNER, roomService.getRole("test-room-123", "owner-user"));
        assertNull(roomService.getRole("test-room-123", "stranger"));
    }

    @Test
    @DisplayName("Should return true when room exists")
    void roomExists_shouldReturnTrueWhenExists() {
        when(roomRepository.existsByRoomId("test-room-123")).thenReturn(true);
        assertTrue(roomService.roomExists("test-room-123"));
    }
}
