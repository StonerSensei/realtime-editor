package com.collabeditor.realtime_editor;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface RoomRepo extends MongoRepository<Room, String> {
    Optional<Room> findByRoomId(String roomId);
}
