package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.response.InvitationResponse;
import com.collabeditor.realtime_editor.dto.response.RoomResponse;
import com.collabeditor.realtime_editor.exception.ForbiddenActionException;
import com.collabeditor.realtime_editor.exception.RoomAlreadyExistsException;
import com.collabeditor.realtime_editor.exception.RoomNotFoundException;
import com.collabeditor.realtime_editor.model.Role;
import com.collabeditor.realtime_editor.model.Room;
import com.collabeditor.realtime_editor.repository.RoomRepository;
import com.collabeditor.realtime_editor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RoomService roomService;

    private CreateRoomRequest createRoomRequest;

    @BeforeEach
    void setUp() {
        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setRoomId("test-room-123");
        createRoomRequest.setLanguage("javascript");
    }

    private Room testRoom(String roomId, String owner) {
        return new Room(roomId, "python", owner, "ABC123");
    }

    // ── Create ────────────────────────────────────

    @Test
    @DisplayName("Should create a room with the creator as OWNER and a join code")
    void createRoom_shouldSucceedWithValidRequest() {
        when(roomRepository.existsByRoomId("test-room-123")).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse response = roomService.createRoom(createRoomRequest, "owner-user");

        assertNotNull(response);
        assertEquals("test-room-123", response.getRoomId());
        assertEquals(Role.OWNER, response.getRole());
        assertNotNull(response.getJoinCode(), "Owner should see the join code");
        assertEquals(6, response.getJoinCode().length());
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

    // ── Join with code ────────────────────────────

    @Test
    @DisplayName("Should join with the correct join code as EDITOR")
    void joinRoom_withCorrectCode_shouldSucceed() {
        Room room = testRoom("test-room-123", "someone");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse response = roomService.joinRoom("test-room-123", "new-user", "ABC123");

        assertEquals(Role.EDITOR, response.getRole());
        assertNull(response.getJoinCode(), "Non-owner should not see the join code");
    }

    @Test
    @DisplayName("Should reject an incorrect join code")
    void joinRoom_withWrongCode_shouldThrow() {
        Room room = testRoom("test-room-123", "someone");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.joinRoom("test-room-123", "new-user", "WRONG1"));
    }

    @Test
    @DisplayName("Should reject join without a code when user is not invited")
    void joinRoom_withoutCodeOrInvite_shouldThrow() {
        Room room = testRoom("test-room-123", "someone");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.joinRoom("test-room-123", "new-user", null));
    }

    // ── Join with invitation ──────────────────────

    @Test
    @DisplayName("Invited user can join without a code; invitation is consumed")
    void joinRoom_withInvitation_shouldSucceedWithoutCode() {
        Room room = testRoom("test-room-123", "someone");
        room.getInvitedUsers().add("invited-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse response = roomService.joinRoom("test-room-123", "invited-user", null);

        assertEquals(Role.EDITOR, response.getRole());
        assertFalse(room.getInvitedUsers().contains("invited-user"), "Invitation should be consumed");
    }

    // ── Existing members ──────────────────────────

    @Test
    @DisplayName("Existing member can rejoin without a code")
    void joinRoom_existingMember_shouldNotRequireCode() {
        Room room = testRoom("test-room-123", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        RoomResponse response = roomService.joinRoom("test-room-123", "owner-user", null);

        assertEquals(Role.OWNER, response.getRole());
        verify(roomRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw exception when joining non-existent room")
    void joinRoom_shouldThrowWhenRoomNotFound() {
        when(roomRepository.findByRoomId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(RoomNotFoundException.class,
                () -> roomService.joinRoom("nonexistent", "user", "CODE12"));
    }

    // ── Invitations ───────────────────────────────

    @Test
    @DisplayName("Owner can invite an existing user")
    void invite_shouldSucceed() {
        Room room = testRoom("test-room-123", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        roomService.invite("test-room-123", "owner-user", "alice");

        assertTrue(room.getInvitedUsers().contains("alice"));
        verify(roomRepository).save(room);
    }

    @Test
    @DisplayName("Invite fails for a non-existent user")
    void invite_nonExistentUser_shouldThrow() {
        Room room = testRoom("test-room-123", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(userRepository.existsByUsername("ghost")).thenReturn(false);

        assertThrows(ForbiddenActionException.class,
                () -> roomService.invite("test-room-123", "owner-user", "ghost"));
    }

    @Test
    @DisplayName("Non-owner cannot invite")
    void invite_byNonOwner_shouldThrow() {
        Room room = testRoom("test-room-123", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.invite("test-room-123", "member-user", "alice"));
    }

    @Test
    @DisplayName("getInvitations returns rooms the user has been invited to")
    void getInvitations_shouldReturnPendingInvitations() {
        Room room = testRoom("proj-x", "owner");
        when(roomRepository.findByInvitedUsersContaining("alice")).thenReturn(List.of(room));

        List<InvitationResponse> invitations = roomService.getInvitations("alice");

        assertEquals(1, invitations.size());
        assertEquals("proj-x", invitations.get(0).getRoomId());
        assertEquals("owner", invitations.get(0).getOwner());
    }

    // ── Join code regeneration ────────────────────

    @Test
    @DisplayName("Owner can regenerate the join code")
    void regenerateJoinCode_shouldReturnNewCode() {
        Room room = testRoom("test-room-123", "owner-user");
        String oldCode = room.getJoinCode();
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        String newCode = roomService.regenerateJoinCode("test-room-123", "owner-user");

        assertEquals(6, newCode.length());
        // The new code could theoretically match the old one (1 in ~10^9), but practically won't.
        verify(roomRepository).save(room);
    }

    // ── Existing tests ────────────────────────────

    @Test
    @DisplayName("Owner should be able to change a member's role")
    void changeRole_shouldSucceedForOwner() {
        Room room = testRoom("test-room-123", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse response = roomService.changeRole("test-room-123", "owner-user", "member-user", Role.VIEWER);

        assertEquals(Role.VIEWER, response.getMembers().stream()
                .filter(m -> m.getUsername().equals("member-user"))
                .findFirst().orElseThrow().getRole());
    }

    @Test
    @DisplayName("Non-owner should not be able to change roles")
    void changeRole_shouldThrowForNonOwner() {
        Room room = testRoom("test-room-123", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.changeRole("test-room-123", "member-user", "owner-user", Role.VIEWER));
    }

    @Test
    @DisplayName("Owner should be able to kick a member")
    void kickMember_shouldSucceedForOwner() {
        Room room = testRoom("test-room-123", "owner-user");
        room.getMembers().put("member-user", Role.EDITOR);
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.kickMember("test-room-123", "owner-user", "member-user");

        assertFalse(room.getMembers().containsKey("member-user"));
    }

    @Test
    @DisplayName("Owner cannot be kicked")
    void kickMember_shouldThrowWhenKickingOwner() {
        Room room = testRoom("test-room-123", "owner-user");
        when(roomRepository.findByRoomId("test-room-123")).thenReturn(Optional.of(room));

        assertThrows(ForbiddenActionException.class,
                () -> roomService.kickMember("test-room-123", "owner-user", "owner-user"));
    }

    @Test
    @DisplayName("Should return the user's role via getRole")
    void getRole_shouldReturnMemberRole() {
        Room room = testRoom("test-room-123", "owner-user");
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