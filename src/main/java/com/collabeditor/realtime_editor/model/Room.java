package com.collabeditor.realtime_editor.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /** 6-character alphanumeric code required to join (unless invited). */
    @Indexed(unique = true)
    private String joinCode;

    /** Usernames that may join without the code (consumed on join). */
    private Set<String> invitedUsers = new HashSet<>();

    /** Usernames that have been kicked; cannot rejoin until un-kicked by the owner. */
    private Set<String> kickedUsers = new HashSet<>();

    private Instant createdAt;

    public Room(String roomId, String language, String owner, String joinCode) {
        this.roomId = roomId;
        this.language = language;
        this.owner = owner;
        this.joinCode = joinCode;
        this.defaultRole = Role.EDITOR;
        this.members = new HashMap<>();
        this.members.put(owner, Role.OWNER);
        this.invitedUsers = new HashSet<>();
        this.kickedUsers = new HashSet<>();
        this.createdAt = Instant.now();
    }
}