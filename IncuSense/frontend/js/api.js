(function () {
    const TOKEN_KEY = 'incusense.jwt';

    async function request(path, options = {}) {
        const headers = new Headers(options.headers || {});
        headers.set('Content-Type', 'application/json');

        const token = localStorage.getItem(TOKEN_KEY);
        if (token) {
            headers.set('Authorization', `Bearer ${token}`);
        }

        const response = await fetch(path, { ...options, headers });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || `Request failed with status ${response.status}`);
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    }

    window.IncuSenseApi = {
        async login(username, password) {
            const auth = await request('/api/auth/login', {
                method: 'POST',
                body: JSON.stringify({ username, password })
            });
            localStorage.setItem(TOKEN_KEY, auth.token);
            return auth;
        },
        logout() {
            localStorage.removeItem(TOKEN_KEY);
            window.location.href = '/';
        },
        requireAuth() {
            if (!localStorage.getItem(TOKEN_KEY)) {
                window.location.href = '/';
            }
        },
        latestMeasurements(limit = 100) {
            return request(`/api/measurements/latest?limit=${encodeURIComponent(limit)}`);
        },
        history() {
            return request('/api/measurements/history');
        },
        alerts() {
            return request('/api/alerts');
        }
    };
})();
