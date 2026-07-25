package com.collabeditor.realtime_editor.model;

/**
 * Access level a user holds within a room.
 * <ul>
 *   <li>{@code OWNER}  - created the room; can manage members, change roles, kick.</li>
 *   <li>{@code EDITOR} - can read and edit the shared document.</li>
 *   <li>{@code VIEWER} - read-only; cannot modify the document.</li>
 * </ul>
 */
public enum Role {
    OWNER,
    EDITOR,
    VIEWER
}
