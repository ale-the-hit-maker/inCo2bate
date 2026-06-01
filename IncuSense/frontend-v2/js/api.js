(function () {
    const TOKEN_KEY = 'incusense.jwt';
    const LAB_KEY = 'incusense.labId';

    async function request(path, options = {}) {
        const headers = new Headers(options.headers || {});
        headers.set('Content-Type', 'application/json');

        const token = localStorage.getItem(TOKEN_KEY);
        if (token) {
            headers.set('Authorization', `Bearer ${token}`);
        }

        const response = await fetch(path, { ...options, headers });
        if (!response.ok) {
            let message = '';
            try { message = await response.text(); } catch (e) { /* ignore */ }
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
            if (auth.labId) localStorage.setItem(LAB_KEY, auth.labId);
            return auth;
        },
        async register(payload) {
            const auth = await request('/api/auth/register', {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            localStorage.setItem(TOKEN_KEY, auth.token);
            if (auth.labId) localStorage.setItem(LAB_KEY, auth.labId);
            return auth;
        },
        logout() {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(LAB_KEY);
            window.location.href = '/';
        },
        requireAuth() {
            if (!localStorage.getItem(TOKEN_KEY)) {
                window.location.href = '/';
            }
        },
        labId() {
            return localStorage.getItem(LAB_KEY);
        },
        lab() {
            return request('/api/lab');
        },
        hubs() {
            return request('/api/hubs');
        },
        latestMeasurements(limit = 100) {
            return request(`/api/measurements/latest?limit=${encodeURIComponent(limit)}`);
        },
        measurementsForHub(hubKey, limit = 200) {
            return request(`/api/hubs/${encodeURIComponent(hubKey)}/measurements?limit=${encodeURIComponent(limit)}`);
        },
        history(days = 180) {
            return request(`/api/measurements/history?days=${encodeURIComponent(days)}`);
        },
        alerts() {
            return request('/api/alerts');
        },
        hubHealth(hubKey) {
            return request(`/api/hubs/${encodeURIComponent(hubKey)}/health`);
        },
        driftCurves() {
            return request('/api/drift-curves');
        },
        assignCurve(hubKey, curveId, installResponse) {
            return request(`/api/hubs/${encodeURIComponent(hubKey)}/health/assign-curve`, {
                method: 'POST',
                body: JSON.stringify({ curveId, installResponse })
            });
        },
        notificationContacts() {
            return request('/api/notification-contacts');
        },
        createContact(contact) {
            return request('/api/notification-contacts', { method: 'POST', body: JSON.stringify(contact) });
        },
        updateContact(id, contact) {
            return request(`/api/notification-contacts/${id}`, { method: 'PUT', body: JSON.stringify(contact) });
        },
        deleteContact(id) {
            return request(`/api/notification-contacts/${id}`, { method: 'DELETE' });
        }
    };
})();
