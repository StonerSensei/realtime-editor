package com.collabeditor.realtime_editor.dto.response;

import com.collabeditor.realtime_editor.model.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoomResponse {

    private String roomId;
    private String language;
    private String owner;
    private Role role;
    private List<MemberDto> members;
    private Instant createdAt;
    private String message;
}
