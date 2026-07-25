/**
 * collab.js - Conflict-free real-time collaboration using Yjs (CRDT), multi-file.
 *
 * The room's project is a Yjs Y.Map "files": fileId -> Y.Map { name, content:Y.Text }.
 * Every file operation (create/rename/delete/edit) is a CRDT change that syncs to all
 * peers automatically through the /yjs/{roomId} binary relay. The CodeMirror editor is
 * bound to the *active* file's Y.Text; switching files rebinds. Remote cursors render
 * via the Yjs awareness protocol (per active file).
 *
 * Wire protocol (first byte = message type):
 *   0 SYNC_REQUEST   1 SYNC_UPDATE   2 AWARENESS   3 AWARENESS_QUERY
 *   4 PRESENCE (server->client)      5 ROSTER (server->client)
 */

import * as Y from "yjs";
import { CodemirrorBinding } from "y-codemirror";
import * as awarenessProtocol from "y-protocols/awareness";

const TYPE_SYNC_REQUEST = 0;
const TYPE_SYNC_UPDATE = 1;
const TYPE_AWARENESS = 2;
const TYPE_AWARENESS_QUERY = 3;
const TYPE_PRESENCE = 4;
const TYPE_ROSTER = 5;

const CLOSE_CODE_KICKED = 4001;

const USER_COLORS = [
    "#bd93f9", "#ff79c6", "#8be9fd", "#50fa7b",
    "#ffb86c", "#f1fa8c", "#ff5555", "#6272a4"
];

function randomColor() {
    return USER_COLORS[Math.floor(Math.random() * USER_COLORS.length)];
}

function getToken() {
    return localStorage.getItem("token");
}

function wsUrl(path) {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${window.location.host}${path}`;
}

function uid() {
    return "f-" + Math.random().toString(36).slice(2, 10);
}

function extToLang(name) {
    const ext = (name.split(".").pop() || "").toLowerCase();
    return { js: "javascript", py: "python", cpp: "cpp", cc: "cpp", cxx: "cpp", c: "c", java: "java" }[ext] || null;
}

function langToMode(lang) {
    return {
        javascript: "javascript",
        cpp: "text/x-c++src",
        c: "text/x-csrc",
        python: "python",
        java: "text/x-java"
    }[lang] || "text/plain";
}

function defaultFileName(language) {
    return {
        javascript: "main.js",
        python: "main.py",
        cpp: "main.cpp",
        c: "main.c",
        java: "Main.java"
    }[language] || "main.txt";
}

const STARTER_CODE = {
    javascript: '// Welcome to CollabIDE!\n// Start coding collaboratively\n\nconsole.log("Hello, World!");',
    python: '# Welcome to CollabIDE!\n# Start coding collaboratively\n\nprint("Hello, World!")',
    cpp: '// Welcome to CollabIDE!\n\n#include <iostream>\nusing namespace std;\n\nint main() {\n    cout << "Hello, World!" << endl;\n    return 0;\n}',
    c: '// Welcome to CollabIDE!\n\n#include <stdio.h>\n\nint main() {\n    printf("Hello, World!\\n");\n    return 0;\n}',
    java: '// Welcome to CollabIDE!\n\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, World!");\n    }\n}'
};

function initCollab(editor, roomId, language) {
    const doc = new Y.Doc();
    const filesMap = doc.getMap("files");     // fileId -> Y.Map({ name, content:Y.Text })
    const awareness = new awarenessProtocol.Awareness(doc);

    const username = localStorage.getItem("username") || "Anonymous";
    awareness.setLocalStateField("user", { name: username, color: randomColor() });

    let binding = null;
    let activeFileId = null;

    // Nothing is bound yet - keep the editor read-only until the first file opens.
    editor.setOption("readOnly", true);

    // ── File helpers ──────────────────────────────
    function getFiles() {
        return Array.from(filesMap.keys())
            .map(id => ({ id, name: filesMap.get(id).get("name") }))
            .sort((a, b) => a.name.localeCompare(b.name));
    }

    function firstFileId() {
        const files = getFiles();
        return files.length ? files[0].id : null;
    }

    function createFileInternal(name, content) {
        const id = uid();
        doc.transact(() => {
            const fileMap = new Y.Map();
            filesMap.set(id, fileMap);
            fileMap.set("name", name);
            const text = new Y.Text();
            fileMap.set("content", text);
            if (content) text.insert(0, content);
        });
        return id;
    }

    function openFile(id) {
        const fileMap = filesMap.get(id);
        if (!fileMap) return;

        if (binding) {
            try { binding.destroy(); } catch (e) { /* ignore */ }
            binding = null;
        }
        activeFileId = id;
        const ytext = fileMap.get("content");
        binding = new CodemirrorBinding(ytext, editor, awareness);

        const name = fileMap.get("name");
        editor.setOption("mode", langToMode(extToLang(name)));
        const fn = document.getElementById("filename");
        if (fn) fn.textContent = name;

        window.dispatchEvent(new CustomEvent("active-file-changed", {
            detail: { id, name, language: extToLang(name) }
        }));
    }

    // ── Public file API (used by files.js / editor.js / rooms.js) ──
    window.Collab = {
        getFiles,
        getActiveFileId: () => activeFileId,
        getActiveFileName: () => (activeFileId && filesMap.get(activeFileId))
            ? filesMap.get(activeFileId).get("name") : null,
        getActiveLanguage: () => {
            const fileMap = activeFileId && filesMap.get(activeFileId);
            return fileMap ? extToLang(fileMap.get("name")) : null;
        },
        openFile,
        createFile(name) {
            name = (name || "").trim();
            if (!name) return null;
            if (getFiles().some(f => f.name.toLowerCase() === name.toLowerCase())) return null;
            const id = createFileInternal(name, "");
            openFile(id);
            return id;
        },
        renameFile(id, newName) {
            newName = (newName || "").trim();
            const fileMap = filesMap.get(id);
            if (!fileMap || !newName) return false;
            if (getFiles().some(f => f.id !== id && f.name.toLowerCase() === newName.toLowerCase())) return false;
            fileMap.set("name", newName);
            if (id === activeFileId) {
                editor.setOption("mode", langToMode(extToLang(newName)));
                const fn = document.getElementById("filename");
                if (fn) fn.textContent = newName;
                window.dispatchEvent(new CustomEvent("active-file-changed",
                    { detail: { id, name: newName, language: extToLang(newName) } }));
            }
            return true;
        },
        deleteFile(id) {
            if (filesMap.size <= 1) return false;   // always keep at least one file
            const wasActive = id === activeFileId;
            filesMap.delete(id);
            if (wasActive) {
                const first = firstFileId();
                if (first) openFile(first);
            }
            return true;
        },
        getProjectFiles() {
            const out = {};
            filesMap.forEach((fileMap) => { out[fileMap.get("name")] = fileMap.get("content").toString(); });
            return out;
        },
        replaceProject(filesObj) {
            doc.transact(() => {
                Array.from(filesMap.keys()).forEach(k => filesMap.delete(k));
            });
            Object.entries(filesObj || {}).forEach(([name, content]) => createFileInternal(name, content));
            const first = firstFileId();
            if (first) openFile(first);
        },
        replaceContent(text) {
            const fileMap = activeFileId && filesMap.get(activeFileId);
            if (!fileMap) return;
            const ytext = fileMap.get("content");
            doc.transact(() => {
                ytext.delete(0, ytext.length);
                if (text) ytext.insert(0, text);
            });
        },
        onFilesChanged(cb) { window.addEventListener("files-changed", () => cb(getFiles())); },
        getParticipants() {
            return Array.from(awareness.getStates().values()).map(s => s.user).filter(Boolean);
        },
        onParticipants(cb) {
            awareness.on("update", () => cb(window.Collab.getParticipants()));
            cb(window.Collab.getParticipants());
        }
    };

    // React to structural file changes (add/remove/rename) from any peer.
    filesMap.observeDeep((events) => {
        const structural = events.some(e =>
            e.target === filesMap || (e.keysChanged && e.keysChanged.has("name")));
        if (!structural) return;

        // If we have no active file (or it was deleted), open one.
        if (!activeFileId || !filesMap.has(activeFileId)) {
            const first = firstFileId();
            if (first) openFile(first);
        }
        window.dispatchEvent(new CustomEvent("files-changed"));
    });

    window.dispatchEvent(new CustomEvent("collab-ready"));

    // ── Networking state ──────────────────────────
    let ws = null;
    let isFirst = false;
    let seeded = false;
    let reconnectAttempts = 0;
    let reconnectTimer = null;
    let intentionalClose = false;
    let roster = [];
    const MAX_RECONNECT = 10;

    window.Collab.getRoster = () => roster;

    // ── UI helpers ────────────────────────────────
    function setStatus(status) {
        const statusEl = document.getElementById("connectionStatus");
        const indicator = document.querySelector(".status-indicator");
        if (!statusEl || !indicator) return;
        const states = {
            connected: ["Connected", "var(--success)"],
            disconnected: ["Disconnected", "var(--error)"],
            reconnecting: [`Reconnecting (${reconnectAttempts})...`, "var(--warning)"],
            error: ["Connection Error", "var(--error)"]
        };
        const [text, color] = states[status] || states.disconnected;
        statusEl.textContent = text;
        indicator.style.background = color;
    }

    function updateOnlineCount() {
        const el = document.getElementById("userCount");
        if (el) el.textContent = roster.length || awareness.getStates().size;
    }

    // ── Message encoding ──────────────────────────
    function frame(type, payload) {
        const body = payload || new Uint8Array(0);
        const msg = new Uint8Array(1 + body.length);
        msg[0] = type;
        msg.set(body, 1);
        return msg;
    }

    function send(type, payload) {
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(frame(type, payload));
    }

    // ── Seeding (only the first peer seeds the project from the latest snapshot) ──
    async function maybeSeed() {
        if (seeded || !isFirst) return;
        seeded = true;

        let snap = null;
        try {
            const res = await fetch(`/api/snapshots/latest/${roomId}`, {
                headers: { "Authorization": "Bearer " + getToken() }
            });
            if (res.ok) snap = await res.json();
        } catch (e) { /* no snapshot */ }

        if (filesMap.size === 0) {
            if (snap && snap.files && Object.keys(snap.files).length) {
                Object.entries(snap.files).forEach(([name, content]) => createFileInternal(name, content));
            } else if (snap && snap.code && snap.code.trim()) {
                createFileInternal(defaultFileName(language), snap.code);
            } else {
                createFileInternal(defaultFileName(language), STARTER_CODE[language] || "");
            }
        }

        const first = firstFileId();
        if (first) openFile(first);
    }

    // ── Yjs -> network ────────────────────────────
    doc.on("update", (update, origin) => {
        if (origin !== "remote") send(TYPE_SYNC_UPDATE, update);
    });

    awareness.on("update", ({ added, updated, removed }) => {
        const changed = added.concat(updated, removed);
        send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(awareness, changed));
        updateOnlineCount();
    });

    // ── Network -> Yjs ────────────────────────────
    function handleMessage(data) {
        const type = data[0];
        const payload = data.subarray(1);

        switch (type) {
            case TYPE_SYNC_REQUEST:
                send(TYPE_SYNC_UPDATE, Y.encodeStateAsUpdate(doc, payload));
                break;
            case TYPE_SYNC_UPDATE:
                Y.applyUpdate(doc, payload, "remote");
                break;
            case TYPE_AWARENESS:
                awarenessProtocol.applyAwarenessUpdate(awareness, payload, "remote");
                updateOnlineCount();
                break;
            case TYPE_AWARENESS_QUERY:
                send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(
                    awareness, Array.from(awareness.getStates().keys())));
                break;
            case TYPE_PRESENCE:
                isFirst = payload[0] === 1;
                maybeSeed();
                break;
            case TYPE_ROSTER:
                try {
                    roster = JSON.parse(new TextDecoder().decode(payload));
                    updateOnlineCount();
                    window.dispatchEvent(new CustomEvent("roster", { detail: { roster } }));
                } catch (err) {
                    console.error("Roster parse error:", err);
                }
                break;
            default:
                console.warn("Unknown collab message type:", type);
        }
    }

    // ── WebSocket connection with reconnect ───────
    function connect() {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

        ws = new WebSocket(wsUrl(`/yjs/${roomId}?token=${encodeURIComponent(getToken())}`));
        ws.binaryType = "arraybuffer";

        ws.onopen = () => {
            reconnectAttempts = 0;
            setStatus("connected");
            send(TYPE_SYNC_REQUEST, Y.encodeStateVector(doc));
            send(TYPE_SYNC_UPDATE, Y.encodeStateAsUpdate(doc));
            send(TYPE_AWARENESS_QUERY);
            send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(awareness, [doc.clientID]));
            updateOnlineCount();
        };

        ws.onmessage = (e) => {
            try { handleMessage(new Uint8Array(e.data)); }
            catch (err) { console.error("Collab message error:", err); }
        };

        ws.onerror = () => setStatus("error");

        ws.onclose = (event) => {
            if (event.code === CLOSE_CODE_KICKED) {
                intentionalClose = true;
                clearTimeout(reconnectTimer);
                try { if (binding) binding.destroy(); } catch (e) { /* ignore */ }
                window.dispatchEvent(new CustomEvent("kicked", { detail: { reason: event.reason } }));
                return;
            }
            setStatus("disconnected");
            if (!intentionalClose && reconnectAttempts < MAX_RECONNECT) {
                const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);
                reconnectAttempts++;
                setStatus("reconnecting");
                reconnectTimer = setTimeout(connect, delay);
            }
        };
    }

    function cleanup() {
        intentionalClose = true;
        clearTimeout(reconnectTimer);
        awarenessProtocol.removeAwarenessStates(awareness, [doc.clientID], "exit");
        try { if (binding) binding.destroy(); } catch (e) { /* ignore */ }
        if (ws) ws.close();
    }

    window.addEventListener("editor-exit", cleanup);
    window.addEventListener("beforeunload", () => {
        awarenessProtocol.removeAwarenessStates(awareness, [doc.clientID], "unload");
    });

    connect();
    updateOnlineCount();
}

// Wait for editor.js to create the CodeMirror instance
window.addEventListener("editor-ready", (e) => {
    const { editor, roomId, language } = e.detail;
    initCollab(editor, roomId, language);
});
