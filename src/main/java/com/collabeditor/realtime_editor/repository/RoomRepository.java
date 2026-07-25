package com.collabeditor.realtime_editor.repository;

import com.collabeditor.realtime_editor.model.Room;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoomRepository extends MongoRepository<Room, String> {

    Optional<Room> findByRoomId(String roomId);

    boolean existsByRoomId(String roomId);
}
