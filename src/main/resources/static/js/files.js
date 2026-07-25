/**
 * files.js - File explorer for the multi-file room.
 *
 * Renders the Yjs-backed file list (window.Collab) into the "Files" drawer panel and
 * wires create / rename / delete / select. All operations go through window.Collab,
 * so they sync to every peer through the CRDT automatically.
 */

(function () {
    "use strict";

    if (!Auth.requireAuth()) return;

    let myRole = "EDITOR";

    function canEdit() {
        return myRole !== "VIEWER";
    }

    function iconFor(name) {
        const ext = (name.split(".").pop() || "").toLowerCase();
        const map = {
            js: "fab fa-js", py: "fab fa-python", java: "fab fa-java",
            c: "fas fa-file-code", cpp: "fas fa-file-code", cc: "fas fa-file-code",
            html: "fab fa-html5", css: "fab fa-css3-alt", json: "fas fa-file-code",
            md: "fas fa-file-lines", txt: "fas fa-file-lines"
        };
        return map[ext] || "fas fa-file-code";
    }

    function escapeHtml(str) {
        const div = document.createElement("div");
        div.textContent = str == null ? "" : str;
        return div.innerHTML;
    }

    function render() {
        const list = document.getElementById("fileList");
        if (!list || !window.Collab) return;

        const files = window.Collab.getFiles();
        const activeId = window.Collab.getActiveFileId();

        list.innerHTML = "";
        files.forEach(file => {
            const li = document.createElement("li");
            li.className = "file-item" + (file.id === activeId ? " active" : "");
            li.innerHTML = `
                <span class="file-name" title="${escapeHtml(file.name)}">
                    <i class="${iconFor(file.name)}"></i> ${escapeHtml(file.name)}
                </span>
                <span class="file-actions">
                    <button class="file-action-btn" data-act="rename" title="Rename"><i class="fas fa-pen"></i></button>
                    <button class="file-action-btn" data-act="delete" title="Delete"><i class="fas fa-trash"></i></button>
                </span>`;

            li.querySelector(".file-name").addEventListener("click", () => {
                window.Collab.openFile(file.id);
                render();
            });

            const renameBtn = li.querySelector('[data-act="rename"]');
            const deleteBtn = li.querySelector('[data-act="delete"]');

            if (!canEdit()) {
                renameBtn.style.display = "none";
                deleteBtn.style.display = "none";
            }

            renameBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                const newName = prompt("Rename file:", file.name);
                if (newName && newName.trim() && newName !== file.name) {
                    if (!window.Collab.renameFile(file.id, newName.trim())) {
                        Toast.show("A file with that name already exists", "error");
                    }
                }
            });

            deleteBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                if (window.Collab.getFiles().length <= 1) {
                    Toast.show("A room must have at least one file", "warning");
                    return;
                }
                if (confirm(`Delete "${file.name}"? This affects everyone in the room.`)) {
                    window.Collab.deleteFile(file.id);
                }
            });

            list.appendChild(li);
        });
    }

    function createFilePrompt() {
        if (!canEdit()) {
            Toast.show("Viewers cannot create files", "warning");
            return;
        }
        const name = prompt("New file name (e.g. utils.js, helper.py):");
        if (!name || !name.trim()) return;
        const id = window.Collab.createFile(name.trim());
        if (!id) {
            Toast.show("Invalid or duplicate file name", "error");
            return;
        }
        render();
    }

    function wire() {
        if (!window.Collab) return;
        render();
        window.Collab.onFilesChanged(render);
        document.getElementById("newFileBtn")?.addEventListener("click", createFilePrompt);
    }

    // collab.js exposes window.Collab and fires collab-ready.
    if (window.Collab) {
        wire();
    } else {
        window.addEventListener("collab-ready", wire, { once: true });
    }

    // Track our role so viewers can't create/rename/delete.
    window.addEventListener("editor-ready", (e) => { myRole = e.detail.role || "EDITOR"; render(); });
    window.addEventListener("role-changed", (e) => { myRole = e.detail.role; render(); });
    window.addEventListener("active-file-changed", render);
})();
