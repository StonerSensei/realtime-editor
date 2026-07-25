package com.collabeditor.realtime_editor.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Document("rooms")
@Data
@NoArgsConstructor
public class Room {

    @Id
    private String id;

    @Indexed(unique = true)
    private String roomId;

    private String language;

    private String owner;

    private Role defaultRole = Role.EDITOR;

    private Map<String, Role> members = new HashMap<>();

    private Instant createdAt;

    public Room(String roomId, String language, String owner) {
        this.roomId = roomId;
        this.language = language;
        this.owner = owner;
        this.defaultRole = Role.EDITOR;
        this.members = new HashMap<>();
        this.members.put(owner, Role.OWNER);
        this.createdAt = Instant.now();
    }
}
