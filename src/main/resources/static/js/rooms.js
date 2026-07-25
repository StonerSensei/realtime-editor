/**
 * rooms.js - Room collaboration UI: side drawer with Chat, Participants, and
 * Version History. Depends on auth.js (Auth/API/Toast) and collab.js (window.Collab).
 */

(function () {
    "use strict";

    if (!Auth.requireAuth()) return;

    let ctx = null;              // { roomId, role, owner, members, username }
    let membersByName = {};      // username -> role
    let chatWs = null;
    let activeDrawer = null;
    let participantsWired = false;

    // ── Helpers ───────────────────────────────────
    function wsUrl(path) {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        return `${protocol}//${window.location.host}${path}`;
    }

    function escapeHtml(str) {
        const div = document.createElement("div");
        div.textContent = str == null ? "" : str;
        return div.innerHTML;
    }

    function formatTime(iso) {
        try {
            return new Date(iso).toLocaleString([], { dateStyle: "short", timeStyle: "short" });
        } catch {
            return iso;
        }
    }

    function debounce(fn, delay) {
        let t;
        return (...args) => {
            clearTimeout(t);
            t = setTimeout(() => fn(...args), delay);
        };
    }

    function isOwner() {
        return ctx && ctx.role === "OWNER";
    }

    /**
     * Re-fetches the room's current members + our own role from the server, so the
     * participant list stays accurate as people join/leave or get promoted/demoted.
     */
    async function refreshMembers() {
        if (!ctx) return;
        try {
            const details = await API.get(`/api/rooms/${ctx.roomId}`);
            membersByName = {};
            (details.members || []).forEach(m => { membersByName[m.username] = m.role; });
            if (details.owner) ctx.owner = details.owner;

            // Detect our own role change (promoted/demoted by the owner)
            const newRole = details.role || ctx.role;
            if (newRole !== ctx.role) {
                ctx.role = newRole;
                window.dispatchEvent(new CustomEvent("role-changed", { detail: { role: newRole } }));
                Toast.show(`Your role is now ${newRole}`, "info");
            }

            if (activeDrawer === "participants") renderParticipants();
        } catch (e) {
            /* ignore transient failures */
        }
    }

    // ── Drawer toggling ───────────────────────────
    function setupDrawer() {
        const drawer = document.getElementById("drawer");
        const title = document.getElementById("drawerTitle");
        const titles = { files: "Files", chat: "Chat", participants: "Participants", history: "Version History" };

        document.querySelectorAll(".sidebar-btn").forEach(btn => {
            btn.addEventListener("click", () => {
                const which = btn.dataset.drawer;
                if (activeDrawer === which) {
                    closeDrawer();
                    return;
                }
                activeDrawer = which;

                document.querySelectorAll(".sidebar-btn").forEach(b => b.classList.remove("active"));
                btn.classList.add("active");

                document.querySelectorAll(".drawer-panel").forEach(p => p.classList.add("hidden"));
                document.getElementById(`panel-${which}`)?.classList.remove("hidden");

                title.textContent = titles[which] || "";
                drawer.classList.remove("hidden");

                if (which === "participants") { renderParticipants(); refreshMembers(); }
                if (which === "history") loadHistory();
            });
        });

        document.getElementById("drawerClose")?.addEventListener("click", closeDrawer);
    }

    function closeDrawer() {
        document.getElementById("drawer")?.classList.add("hidden");
        document.querySelectorAll(".sidebar-btn").forEach(b => b.classList.remove("active"));
        activeDrawer = null;
    }

    // ── Chat ──────────────────────────────────────
    function setupChat() {
        const messagesEl = document.getElementById("chatMessages");
        const input = document.getElementById("chatInput");
        const sendBtn = document.getElementById("chatSend");

        // Load history over REST
        API.get(`/api/chat/${ctx.roomId}`)
            .then(msgs => (msgs || []).forEach(appendChatMessage))
            .catch(() => { /* empty history */ });

        // Live channel
        connectChat();

        function send() {
            const content = input.value.trim();
            if (!content) return;
            if (chatWs && chatWs.readyState === WebSocket.OPEN) {
                chatWs.send(JSON.stringify({ content }));
                input.value = "";
            } else {
                Toast.show("Chat not connected", "warning");
            }
        }

        sendBtn?.addEventListener("click", send);
        input?.addEventListener("keypress", (e) => {
            if (e.key === "Enter") send();
        });

        function appendChatMessage(msg) {
            const mine = msg.username === ctx.username;
            const el = document.createElement("div");
            el.className = "chat-msg" + (mine ? " mine" : "");
            el.innerHTML = `
                <div class="chat-msg-head">
                    <span class="chat-author">${escapeHtml(msg.username)}</span>
                    <span class="chat-time">${formatTime(msg.timestamp)}</span>
                </div>
                <div class="chat-body">${escapeHtml(msg.content)}</div>`;
            messagesEl.appendChild(el);
            messagesEl.scrollTop = messagesEl.scrollHeight;
        }

        window.__appendChatMessage = appendChatMessage;
    }

    function connectChat() {
        chatWs = new WebSocket(wsUrl(`/ws/chat/${ctx.roomId}?token=${encodeURIComponent(Auth.getToken())}`));
        chatWs.onmessage = (e) => {
            try {
                const msg = JSON.parse(e.data);
                window.__appendChatMessage?.(msg);
            } catch (err) {
                console.error("Chat parse error:", err);
            }
        };
        chatWs.onclose = () => {
            // Reconnect after a short delay unless we're leaving
            setTimeout(() => {
                if (!window.__leaving) connectChat();
            }, 2000);
        };
    }

    // ── Participants ──────────────────────────────
    function wireParticipants() {
        if (participantsWired || !window.Collab) return;
        participantsWired = true;
        const debouncedRefresh = debounce(refreshMembers, 1200);
        window.Collab.onParticipants(() => {
            debouncedRefresh();           // someone joined/left -> refresh roles
            if (activeDrawer === "participants") renderParticipants();
        });
    }

    function renderParticipants() {
        const list = document.getElementById("participantList");
        if (!list) return;

        // Server roster is the authoritative "who's connected" list; awareness
        // states are only used to look up each user's cursor color.
        const awarenessUsers = window.Collab ? window.Collab.getParticipants() : [];
        const roster = (window.Collab && window.Collab.getRoster) ? window.Collab.getRoster() : [];
        const online = awarenessUsers;
        const onlineNames = new Set(roster.length ? roster : awarenessUsers.map(u => u.name));

        // Union of online users and known members
        const names = new Set([...onlineNames, ...Object.keys(membersByName)]);

        list.innerHTML = "";
        [...names].sort().forEach(name => {
            const role = membersByName[name] || "GUEST";
            const isOnline = onlineNames.has(name);
            const color = (online.find(u => u.name === name) || {}).color || "#6272a4";

            const li = document.createElement("li");
            li.className = "participant-item";

            let controls = "";
            if (isOwner() && name !== ctx.owner) {
                controls = `
                    <div class="participant-controls">
                        <select data-user="${escapeHtml(name)}" class="role-select">
                            <option value="EDITOR" ${role === "EDITOR" ? "selected" : ""}>Editor</option>
                            <option value="VIEWER" ${role === "VIEWER" ? "selected" : ""}>Viewer</option>
                        </select>
                        <button class="icon-btn kick-btn" data-user="${escapeHtml(name)}" title="Remove">
                            <i class="fas fa-user-slash"></i>
                        </button>
                    </div>`;
            }

            li.innerHTML = `
                <span class="presence-dot" style="background:${isOnline ? color : "#555"}"></span>
                <span class="participant-name">${escapeHtml(name)}</span>
                <span class="role-badge role-${role.toLowerCase()}">${role}</span>
                ${controls}`;
            list.appendChild(li);
        });

        // Wire owner controls
        list.querySelectorAll(".role-select").forEach(sel => {
            sel.addEventListener("change", () => changeRole(sel.dataset.user, sel.value));
        });
        list.querySelectorAll(".kick-btn").forEach(btn => {
            btn.addEventListener("click", () => kickMember(btn.dataset.user));
        });
    }

    async function changeRole(username, role) {
        try {
            const details = await API.put(`/api/rooms/${ctx.roomId}/members/${encodeURIComponent(username)}/role`, { role });
            updateMembers(details.members);
            Toast.show(`${username} is now ${role}`, "success");
        } catch (err) {
            Toast.show(err.message, "error");
        }
    }

    async function kickMember(username) {
        if (!confirm(`Remove ${username} from the room?`)) return;
        try {
            await API.del(`/api/rooms/${ctx.roomId}/members/${encodeURIComponent(username)}`);
            delete membersByName[username];
            renderParticipants();
            Toast.show(`${username} removed`, "info");
        } catch (err) {
            Toast.show(err.message, "error");
        }
    }

    function updateMembers(members) {
        membersByName = {};
        (members || []).forEach(m => { membersByName[m.username] = m.role; });
        renderParticipants();
    }

    // ── Version History ───────────────────────────
    async function loadHistory() {
        const list = document.getElementById("historyList");
        if (!list) return;
        list.innerHTML = `<li class="hint">Loading...</li>`;

        try {
            const snapshots = await API.get(`/api/snapshots/${ctx.roomId}`);
            if (!snapshots || snapshots.length === 0) {
                list.innerHTML = `<li class="hint">No saved versions yet.</li>`;
                return;
            }

            list.innerHTML = "";
            snapshots.forEach((snap, idx) => {
                let preview;
                if (snap.files && Object.keys(snap.files).length) {
                    const names = Object.keys(snap.files);
                    preview = `${names.length} file${names.length > 1 ? "s" : ""}: ${names.slice(0, 3).join(", ")}`;
                } else {
                    preview = (snap.code || "").split("\n")[0].slice(0, 40);
                }
                const li = document.createElement("li");
                li.className = "history-item";
                li.innerHTML = `
                    <div class="history-meta">
                        <span class="history-when">${formatTime(snap.timestamp)}</span>
                        <span class="history-author">${escapeHtml(snap.savedBy || "unknown")}</span>
                    </div>
                    <div class="history-preview">${escapeHtml(preview) || "&nbsp;"}</div>
                    <button class="btn btn-primary history-restore" data-id="${snap.id}"
                        ${ctx.role === "VIEWER" ? "disabled" : ""}>
                        <i class="fas fa-rotate-left"></i> Restore
                    </button>`;
                list.appendChild(li);
            });

            list.querySelectorAll(".history-restore").forEach(btn => {
                btn.addEventListener("click", () => restoreVersion(btn.dataset.id));
            });
        } catch (err) {
            list.innerHTML = `<li class="hint">Failed to load history.</li>`;
        }
    }

    async function restoreVersion(id) {
        if (ctx.role === "VIEWER") {
            Toast.show("Viewers cannot restore versions", "warning");
            return;
        }
        if (!confirm("Restore this version? Current content will be replaced for everyone.")) return;
        try {
            const snap = await API.get(`/api/snapshots/get/${id}`);
            if (!window.Collab || !snap) return;

            if (snap.files && Object.keys(snap.files).length) {
                window.Collab.replaceProject(snap.files);
                Toast.show("Project version restored", "success");
            } else if (snap.code != null) {
                window.Collab.replaceContent(snap.code);
                Toast.show("Version restored", "success");
            }
        } catch (err) {
            Toast.show(err.message, "error");
        }
    }

    // ── Bootstrap ─────────────────────────────────
    window.addEventListener("editor-ready", (e) => {
        ctx = {
            roomId: e.detail.roomId,
            role: e.detail.role || "EDITOR",
            owner: e.detail.owner,
            members: e.detail.members || [],
            username: e.detail.username
        };
        updateMembers(ctx.members);

        setupDrawer();
        setupChat();
        if (window.Collab) wireParticipants();
    });

    // collab.js may finish after editor-ready; wire participant live updates then.
    window.addEventListener("collab-ready", wireParticipants);

    // Poll periodically so role changes (and late joiners) are reflected even
    // when the participants drawer is closed.
    setInterval(() => { if (ctx) refreshMembers(); }, 10000);

    // Server roster changed (someone joined/left/was kicked) -> refresh the list.
    window.addEventListener("roster", () => {
        if (activeDrawer === "participants") renderParticipants();
    });

    // We were removed from the room by the owner: notify and return to the lobby.
    window.addEventListener("kicked", () => {
        window.__leaving = true;
        if (chatWs) chatWs.close();
        Toast.show("You were removed from the room by the owner", "error");
        setTimeout(() => { window.location.href = "/index.html"; }, 1800);
    });

    window.addEventListener("beforeunload", () => {
        window.__leaving = true;
        if (chatWs) chatWs.close();
    });
    window.addEventListener("editor-exit", () => {
        window.__leaving = true;
        if (chatWs) chatWs.close();
    });
})();
