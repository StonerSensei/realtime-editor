/**
 * auth.js - Shared authentication utilities for CollabIDE.
 *
 * Access tokens are short-lived JWTs; refresh tokens are long-lived and revocable.
 * The API helper transparently refreshes an expired access token on a 401 and
 * retries the request once (silent renewal), and a background timer refreshes the
 * token before it expires so long-lived WebSocket reconnects keep working.
 */

const Auth = {
    getToken() {
        return localStorage.getItem("token");
    },

    getRefreshToken() {
        return localStorage.getItem("refreshToken");
    },

    getUsername() {
        return localStorage.getItem("username");
    },

    setCredentials(token, username, refreshToken) {
        localStorage.setItem("token", token);
        if (username) localStorage.setItem("username", username);
        if (refreshToken) localStorage.setItem("refreshToken", refreshToken);
    },

    clear() {
        localStorage.removeItem("token");
        localStorage.removeItem("refreshToken");
        localStorage.removeItem("username");
    },

    isAuthenticated() {
        return !!this.getToken();
    },

    headers(contentType = "application/json") {
        return {
            "Content-Type": contentType,
            "Authorization": "Bearer " + this.getToken()
        };
    },

    requireAuth() {
        if (!this.isAuthenticated()) {
            window.location.href = "/login.html";
            return false;
        }
        return true;
    },

    // Deduped refresh: concurrent 401s share one in-flight refresh call.
    _refreshing: null,

    async refreshAccessToken() {
        const refreshToken = this.getRefreshToken();
        if (!refreshToken) return false;

        if (!this._refreshing) {
            this._refreshing = fetch("/api/auth/refresh", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ refreshToken })
            })
                .then(res => (res.ok ? res.json() : null))
                .then(data => {
                    if (data && data.token) {
                        this.setCredentials(data.token, data.username, data.refreshToken);
                        return true;
                    }
                    return false;
                })
                .catch(() => false)
                .finally(() => { this._refreshing = null; });
        }
        return this._refreshing;
    },

    startAutoRefresh(intervalMs = 20 * 60 * 1000) {
        if (this._autoRefreshTimer) return;
        this._autoRefreshTimer = setInterval(() => {
            if (this.isAuthenticated()) this.refreshAccessToken();
        }, intervalMs);
    },

    async logout() {
        const refreshToken = this.getRefreshToken();
        if (refreshToken) {
            // Best-effort revoke on the server.
            try {
                await fetch("/api/auth/logout", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ refreshToken })
                });
            } catch (e) { /* ignore */ }
        }
        this.clear();
        window.location.href = "/login.html";
    }
};

/**
 * Toast notification utility
 */
const Toast = {
    show(message, type = "success") {
        const existing = document.getElementById("toast");
        if (!existing) return;

        const icons = {
            success: "fas fa-check-circle",
            error: "fas fa-exclamation-circle",
            warning: "fas fa-exclamation-triangle",
            info: "fas fa-info-circle"
        };

        existing.innerHTML = `<i class="${icons[type] || icons.success}"></i> ${message}`;
        existing.className = `toast ${type} show`;

        setTimeout(() => {
            existing.classList.remove("show");
        }, 3000);
    }
};

/**
 * API helper with auth, silent token refresh, and error handling.
 */
const API = {
    get(url) { return this._request("GET", url); },
    post(url, body) { return this._request("POST", url, body); },
    put(url, body) { return this._request("PUT", url, body); },
    del(url) { return this._request("DELETE", url); },

    async _request(method, url, body, _retried = false) {
        const opts = { method, headers: Auth.headers() };
        if (body !== undefined) opts.body = JSON.stringify(body);

        const res = await fetch(url, opts);

        if (res.status === 401 && !_retried) {
            // Access token likely expired: try a silent refresh, then retry once.
            const refreshed = await Auth.refreshAccessToken();
            if (refreshed) {
                return this._request(method, url, body, true);
            }
            Auth.clear();
            window.location.href = "/login.html";
            return;
        }

        const data = await res.json().catch(() => null);
        if (!res.ok) {
            const msg = data?.message || data?.error || "Request failed";
            throw new Error(msg);
        }
        return data;
    }
};

// Keep the access token fresh in the background (no-op on the login page, which
// redirects away when already authenticated).
if (Auth.isAuthenticated()) {
    Auth.startAutoRefresh();
}
