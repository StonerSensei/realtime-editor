package com.collabeditor.realtime_editor.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("chat_messages")
@Data
@NoArgsConstructor
public class ChatMessage {

    @Id
    private String id;

    @Indexed
    private String roomId;

    private String username;

    private String content;

    private Instant timestamp;

    public ChatMessage(String roomId, String username, String content) {
        this.roomId = roomId;
        this.username = username;
        this.content = content;
        this.timestamp = Instant.now();
    }
}
