package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.response.ChatMessageResponse;
import com.collabeditor.realtime_editor.model.ChatMessage;
import com.collabeditor.realtime_editor.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageResponse saveMessage(String roomId, String username, String content) {
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CONTENT_LENGTH);
        }

        ChatMessage saved = chatMessageRepository.save(new ChatMessage(roomId, username, trimmed));
        log.debug("Chat message saved in room {} from {}", roomId, username);
        return toResponse(saved);
    }

    /** Returns recent messages for a room in chronological (oldest-first) order. */
    public List<ChatMessageResponse> getRecentMessages(String roomId) {
        return chatMessageRepository.findTop100ByRoomIdOrderByTimestampDesc(roomId).stream()
                .sorted(Comparator.comparing(ChatMessage::getTimestamp))
                .map(this::toResponse)
                .toList();
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return ChatMessageResponse.builder()
                .username(message.getUsername())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .build();
    }
}
