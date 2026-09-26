/**
 * app.js - Index/lobby page logic for CollabIDE
 */

(function () {
    "use strict";

    // Require authentication
    if (!Auth.requireAuth()) return;

    // Display username
    document.addEventListener("DOMContentLoaded", () => {
        const usernameEl = document.getElementById("usernameDisplay");
        if (usernameEl) {
            usernameEl.textContent = Auth.getUsername() || "Guest";
        }
        document.getElementById("roomId")?.focus();
        loadInvitations();
    });

    // Generate random room ID
    function generateRandomId() {
        const adjectives = ["quick", "lazy", "happy", "sleepy", "noisy", "hungry", "brave", "calm"];
        const nouns = ["fox", "dog", "cat", "panda", "koala", "tiger", "eagle", "wolf"];
        const adj = adjectives[Math.floor(Math.random() * adjectives.length)];
        const noun = nouns[Math.floor(Math.random() * nouns.length)];
        return `${adj}-${noun}-${Math.floor(1000 + Math.random() * 9000)}`;
    }

    // Join room
    window.joinRoom = async function () {
        const id = document.getElementById("roomId").value.trim();
        const code = document.getElementById("joinCode").value.trim();
        const language = document.getElementById("language").value;

        if (!id) {
            Toast.show("Enter a Room ID to join", "warning");
            return;
        }

        try {
            const body = code ? { joinCode: code } : {};
            const data = await API.post(`/api/rooms/${id}/join`, body);
            window.location.href = `/editor.html?room=${id}&lang=${data.language || language}`;
        } catch (err) {
            Toast.show(err.message, "error");
        }
    };

    // Host room
    window.hostRoom = async function () {
        const id = document.getElementById("roomId").value.trim() || generateRandomId();
        const language = document.getElementById("language").value;

        try {
            const data = await API.post("/api/rooms/host", { roomId: id, language });
            Toast.show(`Room "${data.roomId}" created! Join code: ${data.joinCode}`, "success");
            window.location.href = `/editor.html?room=${data.roomId}&lang=${data.language}`;
        } catch (err) {
            Toast.show(err.message, "error");
        }
    };

    // Accept invitation (join without code)
    window.acceptInvitation = async function (roomId, language) {
        try {
            await API.post(`/api/rooms/${roomId}/join`, {});
            window.location.href = `/editor.html?room=${roomId}&lang=${language}`;
        } catch (err) {
            Toast.show(err.message, "error");
        }
    };

    // Load pending invitations
    async function loadInvitations() {
        try {
            const invitations = await API.get("/api/rooms/invitations");
            const section = document.getElementById("invitationsSection");
            const list = document.getElementById("invitationList");
            if (!section || !list || !invitations || invitations.length === 0) return;

            section.classList.remove("hidden");
            list.innerHTML = "";
            invitations.forEach(inv => {
                const li = document.createElement("li");
                li.className = "invitation-item";
                li.innerHTML = `
                    <div class="invitation-info">
                        <span class="invitation-room">${escapeHtml(inv.roomId)}</span>
                        <span class="invitation-owner">from ${escapeHtml(inv.owner)} · ${inv.language}</span>
                    </div>
                    <button class="btn btn-success" onclick="acceptInvitation('${escapeHtml(inv.roomId)}', '${escapeHtml(inv.language)}')">
                        <i class="fas fa-check"></i> Join
                    </button>`;
                list.appendChild(li);
            });
        } catch (e) {
            /* no invitations */
        }
    }

    function escapeHtml(str) {
        const div = document.createElement("div");
        div.textContent = str == null ? "" : str;
        return div.innerHTML;
    }

    // Logout
    window.logout = function () {
        Auth.logout();
    };

    // Enter key on room ID input
    document.getElementById("roomId")?.addEventListener("keypress", (e) => {
        if (e.key === "Enter") window.joinRoom();
    });
    document.getElementById("joinCode")?.addEventListener("keypress", (e) => {
        if (e.key === "Enter") window.joinRoom();
    });
})();