package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.service.ChatService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/{roomId}")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(@PathVariable @NotBlank String roomId) {
        return ResponseEntity.ok(chatService.getRecentMessages(roomId));
    }
}
