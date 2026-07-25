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
        const id = document.getElementById("roomId").value.trim() || generateRandomId();
        const language = document.getElementById("language").value;

        try {
            const data = await API.post(`/api/rooms/${id}/join`, {});
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
            Toast.show(`Room "${data.roomId}" created!`, "success");
            window.location.href = `/editor.html?room=${data.roomId}&lang=${data.language}`;
        } catch (err) {
            Toast.show(err.message, "error");
        }
    };

    // Logout
    window.logout = function () {
        Auth.logout();
    };

    // Enter key on room ID input
    document.getElementById("roomId")?.addEventListener("keypress", (e) => {
        if (e.key === "Enter") window.joinRoom();
    });
})();
