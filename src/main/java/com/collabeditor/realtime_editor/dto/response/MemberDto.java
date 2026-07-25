package com.collabeditor.realtime_editor.dto.response;

import com.collabeditor.realtime_editor.model.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MemberDto {
    private String username;
    private Role role;
}
