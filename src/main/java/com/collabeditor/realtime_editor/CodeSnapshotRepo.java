package com.collabeditor.realtime_editor;

import com.collabeditor.realtime_editor.model.CodeSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CodeSnapshotRepo extends MongoRepository<CodeSnapshot, String> {
    List<CodeSnapshot> findByRoomIdOrderByTimestampDesc(String roomId);
}