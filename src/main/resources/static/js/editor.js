/**
 * editor.js - Editor page bootstrap for CollabIDE.
 *
 * Responsible for: creating the CodeMirror instance, code execution, file download,
 * auto-save, resizing, and keyboard shortcuts.
 *
 * Real-time collaboration (Yjs CRDT, remote cursors, presence) is handled separately
 * in collab.js. This file dispatches an "editor-ready" event once the CodeMirror
 * instance exists so collab.js can attach the Yjs binding.
 */

(function () {
    "use strict";

    // Require authentication
    if (!Auth.requireAuth()) return;

    // URL Parameters
    const urlParams = new URLSearchParams(window.location.search);
    const roomId = urlParams.get("room") || "default";
    const language = urlParams.get("lang") || "javascript";

    let editor = null;
    let lastSavedProject = "";

    // ──────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────

    function getWebSocketUrl(endpoint) {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        return `${protocol}//${window.location.host}${endpoint}`;
    }

    function getCodeMirrorMode(lang) {
        const modes = {
            javascript: "javascript",
            cpp: "text/x-c++src",
            c: "text/x-csrc",
            python: "python",
            java: "text/x-java"
        };
        return modes[lang] || "javascript";
    }

    function getFileExtension(lang) {
        const extensions = { javascript: "js", cpp: "cpp", c: "c", python: "py", java: "java" };
        return extensions[lang] || "js";
    }

    // Current user's role in this room (set after join). Defaults to EDITOR.
    let myRole = "EDITOR";

    // ──────────────────────────────────────────────
    // Auto-save (persists current text to MongoDB) - editors/owners only
    // ──────────────────────────────────────────────

    setInterval(() => {
        if (myRole === "VIEWER") return;
        if (!window.Collab || !window.Collab.getProjectFiles) return;

        const files = window.Collab.getProjectFiles();
        if (!Object.keys(files).length) return;

        const serialized = JSON.stringify(files);
        if (serialized === lastSavedProject) return;

        const lang = window.Collab.getActiveLanguage() || language;
        API.post("/api/snapshots/save", { roomId, files, language: lang })
            .then(() => { lastSavedProject = serialized; })
            .catch(err => console.error("Auto-save failed:", err));
    }, 10000);

    // ──────────────────────────────────────────────
    // Code Execution
    // ──────────────────────────────────────────────

    const RUNNABLE = ["javascript", "python", "cpp", "c", "java"];

    function activeFileName() {
        return (window.Collab && window.Collab.getActiveFileName()) || `main.${getFileExtension(language)}`;
    }

    function executeCode() {
        const code = editor.getValue();
        const input = document.getElementById("userInput")?.value || "";
        const output = document.getElementById("output");
        const runLang = (window.Collab && window.Collab.getActiveLanguage()) || language;

        if (!code.trim()) {
            Toast.show("No code to execute", "warning");
            return;
        }
        if (!RUNNABLE.includes(runLang)) {
            const ext = activeFileName().split(".").pop();
            Toast.show(`Can't run .${ext} files`, "warning");
            return;
        }

        output.textContent = `Executing ${activeFileName()}...\n`;
        Toast.show("Executing code...", "info");

        const execSocket = new WebSocket(getWebSocketUrl("/ws/exec"));
        execSocket.onopen = () => execSocket.send(JSON.stringify({ language: runLang, code, input }));
        execSocket.onmessage = (e) => {
            output.textContent += e.data + "\n";
            output.scrollTop = output.scrollHeight;
        };
        execSocket.onerror = () => {
            output.textContent += "Execution error\n";
            Toast.show("Execution error", "error");
        };
        execSocket.onclose = () => {
            output.textContent += "\n--- Finished ---\n";
            Toast.show("Execution completed", "success");
        };
    }

    // ──────────────────────────────────────────────
    // Download
    // ──────────────────────────────────────────────

    function downloadFile() {
        const modal = document.getElementById("filenameModal");
        const input = document.getElementById("filenameInput");
        if (modal && input) {
            const name = activeFileName();
            input.value = name.replace(/\.[^.]+$/, "");     // base name (no extension)
            input.dataset.ext = name.includes(".") ? name.split(".").pop() : getFileExtension(language);
            modal.classList.remove("hidden");
            input.focus();
        }
    }

    function confirmDownload() {
        const input = document.getElementById("filenameInput");
        let base = input?.value.trim();
        if (!base) {
            Toast.show("Please enter a filename", "error");
            return;
        }

        const ext = input.dataset.ext || getFileExtension(language);
        const filename = base.replace(/\s+/g, "_") + "." + ext;
        const blob = new Blob([editor.getValue()], { type: "text/plain" });
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = filename;
        link.click();

        Toast.show(`Downloaded: ${filename}`, "success");
        closeModal();
    }

    function closeModal() {
        document.getElementById("filenameModal")?.classList.add("hidden");
    }

    // ──────────────────────────────────────────────
    // Join room, apply role, then bootstrap collaboration
    // ──────────────────────────────────────────────

    async function joinAndBootstrap() {
        let details = { role: "EDITOR", members: [], owner: null };
        try {
            details = await API.post(`/api/rooms/${roomId}/join`, {});
            myRole = details.role || "EDITOR";
        } catch (err) {
            console.error("Failed to join room:", err);
            Toast.show("Could not load room permissions; defaulting to editor.", "warning");
        }

        applyRole(myRole);

        // Notify collab.js (Yjs binding) and rooms.js (chat/participants/history).
        window.dispatchEvent(new CustomEvent("editor-ready", {
            detail: {
                editor,
                roomId,
                language,
                role: myRole,
                owner: details.owner,
                members: details.members || [],
                username: Auth.getUsername()
            }
        }));
    }

    function applyRole(role) {
        myRole = role;
        const roleText = document.getElementById("roleText");
        if (roleText) roleText.textContent = role;

        const banner = document.getElementById("readOnlyBanner");
        if (role === "VIEWER") {
            editor.setOption("readOnly", "nocursor");
            banner?.classList.remove("hidden");
        } else {
            // EDITOR or OWNER: ensure the editor is writable (handles live promotion)
            editor.setOption("readOnly", false);
            banner?.classList.add("hidden");
        }
    }

    // ──────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────

    document.addEventListener("DOMContentLoaded", () => {
        // Update UI
        document.getElementById("roomDisplay").textContent = roomId;
        document.getElementById("filename").textContent = `main.${getFileExtension(language)}`;

        // Initialize CodeMirror
        editor = CodeMirror.fromTextArea(document.getElementById("code"), {
            lineNumbers: true,
            mode: getCodeMirrorMode(language),
            theme: "dracula",
            lineWrapping: true,
            matchBrackets: true,
            autoCloseBrackets: true,
            indentUnit: 4,
            tabSize: 4,
            extraKeys: {
                "Ctrl-Space": "autocomplete",
                "F11": (cm) => cm.setOption("fullScreen", !cm.getOption("fullScreen")),
                "Esc": (cm) => { if (cm.getOption("fullScreen")) cm.setOption("fullScreen", false); }
            }
        });

        editor.focus();

        // Apply live role changes (e.g., owner promotes/demotes this user)
        window.addEventListener("role-changed", (e) => applyRole(e.detail.role));

        // collab.js keeps the editor read-only until the first file is bound; re-apply
        // our role's read/write state whenever the active file changes.
        window.addEventListener("active-file-changed", () => applyRole(myRole));

        // Fetch our role/membership, then hand the editor to collab.js + rooms.js.
        joinAndBootstrap();

        // Button handlers
        document.getElementById("runBtn")?.addEventListener("click", executeCode);
        document.getElementById("downloadBtn")?.addEventListener("click", downloadFile);
        document.getElementById("confirmDownload")?.addEventListener("click", confirmDownload);
        document.getElementById("cancelDownload")?.addEventListener("click", closeModal);
        document.getElementById("clearOutput")?.addEventListener("click", () => {
            document.getElementById("output").textContent = "Ready to execute code...";
        });
        document.getElementById("exitBtn")?.addEventListener("click", () => {
            // Tell collab.js to tear down the CRDT connection cleanly
            window.dispatchEvent(new CustomEvent("editor-exit"));
            Toast.show("Leaving room...", "info");
            setTimeout(() => { window.location.href = "/index.html"; }, 800);
        });

        // Keyboard shortcuts
        document.addEventListener("keydown", (e) => {
            if (e.ctrlKey || e.metaKey) {
                if (e.key === "s") { e.preventDefault(); downloadFile(); }
                if (e.key === "Enter" && e.shiftKey) { e.preventDefault(); executeCode(); }
                if (e.key === "q" && e.shiftKey) { e.preventDefault(); document.getElementById("exitBtn")?.click(); }
            }
            if (e.key === "Escape") closeModal();
        });

        // Resizable bottom panel
        const bottomPanel = document.getElementById("bottomPanel");
        const resizeHandle = document.getElementById("resizeHandle");
        let isResizing = false, startY = 0, startHeight = 0;

        resizeHandle?.addEventListener("mousedown", (e) => {
            isResizing = true;
            startY = e.clientY;
            startHeight = parseInt(getComputedStyle(bottomPanel).height, 10);
            document.body.style.cursor = "row-resize";
            document.body.style.userSelect = "none";
        });

        document.addEventListener("mousemove", (e) => {
            if (!isResizing) return;
            const newHeight = startHeight - (e.clientY - startY);
            if (newHeight >= 150 && newHeight <= window.innerHeight * 0.6) {
                bottomPanel.style.height = newHeight + "px";
                editor.refresh();
            }
        });

        document.addEventListener("mouseup", () => {
            if (isResizing) {
                isResizing = false;
                document.body.style.cursor = "default";
                document.body.style.userSelect = "auto";
            }
        });
    });
})();
