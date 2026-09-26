package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.exception.ForbiddenActionException;
import com.collabeditor.realtime_editor.service.ChatService;
import com.collabeditor.realtime_editor.service.RoomService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final RoomService roomService;

    @GetMapping("/{roomId}")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(@PathVariable @NotBlank String roomId,
                                                                  Authentication authentication) {
        if (roomService.getRole(roomId, authentication.getName()) == null) {
            throw new ForbiddenActionException("You are not a member of this room");
        }
        return ResponseEntity.ok(chatService.getRecentMessages(roomId));
    }
}