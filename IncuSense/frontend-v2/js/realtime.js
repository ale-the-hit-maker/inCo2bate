(function () {
    // Live updates over STOMP/SockJS. Falls back silently to the 5s REST poll in dashboard.js.
    if (typeof SockJS === 'undefined' || typeof StompJs === 'undefined' || !window.IncuSenseDash) {
        return;
    }

    const labId = IncuSenseApi.labId();
    if (!labId) return;

    let client = null;

    function connect() {
        client = new StompJs.Client({
            webSocketFactory: () => new SockJS('/ws'),
            reconnectDelay: 5000,
            onConnect: () => {
                window.IncuSenseDash.setLive(true);
                client.subscribe('/topic/measurements/' + labId, (msg) => {
                    try { window.IncuSenseDash.onMeasurement(JSON.parse(msg.body)); } catch (e) { /* ignore */ }
                });
                client.subscribe('/topic/alerts/' + labId, (msg) => {
                    try { window.IncuSenseDash.onAlert(JSON.parse(msg.body)); } catch (e) { /* ignore */ }
                });
                client.subscribe('/topic/health/' + labId, (msg) => {
                    try { window.IncuSenseDash.onHealth(JSON.parse(msg.body)); } catch (e) { /* ignore */ }
                });
            },
            onWebSocketClose: () => window.IncuSenseDash.setLive(false),
            onStompError: () => window.IncuSenseDash.setLive(false)
        });
        client.activate();
    }

    connect();
})();
