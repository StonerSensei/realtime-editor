package com.collabeditor.realtime_editor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin(origins = "*") // if needed
public class RoomController {

    private final RoomRepo roomRepo;

    public RoomController(RoomRepo roomRepo) {
        this.roomRepo = roomRepo;
    }

    @PostMapping("/host")
    public ResponseEntity<?> hostRoom(@RequestParam String roomId) {
        System.out.println("Trying to host room: " + roomId);

        Optional<Room> existing = roomRepo.findByRoomId(roomId);
        System.out.println("Existing room found: " + existing.isPresent());

        if (existing.isPresent()) {
            System.out.println("Existing room: " + existing.get());
            return ResponseEntity.badRequest().body("Room already exists.");
        }

        Room newRoom = new Room(roomId);
        Room saved = roomRepo.save(newRoom);
        System.out.println("Saved room: " + saved);

        return ResponseEntity.ok("Room created.");
    }

    @GetMapping("/join")
    public ResponseEntity<?> joinRoom(@RequestParam String roomId) {
        System.out.println("Trying to join room: " + roomId);

        Optional<Room> room = roomRepo.findByRoomId(roomId);
        System.out.println("Room found: " + room.isPresent());

        if (room.isEmpty()) {
            // List all rooms for debugging
            System.out.println("All rooms in DB:");
            roomRepo.findAll().forEach(System.out::println);
            return ResponseEntity.badRequest().body("Room not found.");
        }

        return ResponseEntity.ok("Room joined.");
    }


}
