package com.collabeditor.realtime_editor.exception;

public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(String roomId) {
        super("Room not found: " + roomId);
    }
}
