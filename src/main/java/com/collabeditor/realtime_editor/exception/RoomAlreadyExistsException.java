package com.collabeditor.realtime_editor.exception;

public class RoomAlreadyExistsException extends RuntimeException {

    public RoomAlreadyExistsException(String roomId) {
        super("Room already exists: " + roomId);
    }
}
