package com.collabeditor.realtime_editor;

import com.collabeditor.realtime_editor.model.CodeSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/snapshots")
@CrossOrigin
public class SnapshotController {

    private final CodeSnapshotRepo snapshotRepo;

    public SnapshotController(CodeSnapshotRepo snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveSnapshot(@RequestParam String roomId, @RequestBody String code) {
        CodeSnapshot snap = new CodeSnapshot();
        snap.setRoomId(roomId);
        snap.setCode(code);
        snap.setTimestamp(Instant.now());
        snapshotRepo.save(snap);
        return ResponseEntity.ok("Saved snapshot");
    }

    @GetMapping("/{roomId}")
    public List<CodeSnapshot> getSnapshots(@PathVariable String roomId) {
        return snapshotRepo.findByRoomIdOrderByTimestampDesc(roomId);
    }

    @GetMapping("/get/{id}")
    public ResponseEntity<?> getSnapshot(@PathVariable String id) {
        return snapshotRepo.findById(id)
                .map(snap -> ResponseEntity.ok(snap.getCode()))
                .orElse(ResponseEntity.notFound().build());
    }
}
