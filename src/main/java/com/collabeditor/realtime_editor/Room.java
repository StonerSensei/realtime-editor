package com.collabeditor.realtime_editor;

import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("rooms")
@NoArgsConstructor
public class Room {
    @Id
    private String id;
    private String roomId;

    public Room(String roomId) {
        this.roomId = roomId;
    }

    // Add all getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    // Add toString for debugging
    @Override
    public String toString() {
        return "Room{id='" + id + "', roomId='" + roomId + "'}";
    }
}