package com.collabeditor.realtime_editor.dto.request;

import com.collabeditor.realtime_editor.model.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChangeRoleRequest {

    @NotNull(message = "Role is required")
    private Role role;
}
