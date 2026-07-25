package com.collabeditor.realtime_editor.repository;

import com.collabeditor.realtime_editor.model.CodeSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeSnapshotRepository extends MongoRepository<CodeSnapshot, String> {

    List<CodeSnapshot> findByRoomIdOrderByTimestampDesc(String roomId);

    Optional<CodeSnapshot> findFirstByRoomIdOrderByTimestampDesc(String roomId);

    long countByRoomId(String roomId);

    void deleteByRoomId(String roomId);
}
