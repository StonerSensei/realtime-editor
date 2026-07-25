package com.collabeditor.realtime_editor.repository;

import com.collabeditor.realtime_editor.model.ChatMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findTop100ByRoomIdOrderByTimestampDesc(String roomId);
}
