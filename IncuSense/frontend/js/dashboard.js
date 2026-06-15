(function () {
    IncuSenseApi.requireAuth();

    /* ── DOM refs ── */
    const measurementsBody = document.getElementById('measurements-body');
    const alertsList       = document.getElementById('alerts-list');
    const lastUpdate       = document.getElementById('last-update');
    const metricSelector   = document.getElementById('chart-metric-selector');
    const windowSelector   = document.getElementById('chart-window-selector');
    const hubSelector      = document.getElementById('hub-selector');
    const labBanner        = document.getElementById('lab-banner');
    const liveBadge        = document.getElementById('live-badge');

    let historyChart      = null;
    let cachedHistoryData = [];
    let selectedHub       = '';
    let selectedWindow    = windowSelector.value;          // 'live' oppure numero di giorni
    const LIVE_MAX_POINTS = 180;                           // ~3 minuti a 1 Hz

    const WINDOW_LABELS = { live: 'Live', 1: '24 ore', 7: '7 giorni', 30: '30 giorni', 90: '3 mesi', 180: '6 mesi' };

    function isLiveWindow() { return selectedWindow === 'live'; }

    document.getElementById('logout-button').addEventListener('click', () => IncuSenseApi.logout());

    /* ── Utilities ── */
    function number(value, digits = 1) {
        return Number.isFinite(value)
            ? value.toLocaleString(undefined, { maximumFractionDigits: digits })
            : '--';
    }

    function flash(el) {
        if (!el) return;
        el.classList.remove('value-updated');
        void el.offsetWidth;
        el.classList.add('value-updated');
    }

    function hexToRgb(hex) {
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `${r},${g},${b}`;
    }

    /* ── Metric cards ── */
    function setLatest(measurement) {
        if (!measurement) return;

        const co2El  = document.getElementById('co2-value');
        const tempEl = document.getElementById('temp-value');
        const humEl  = document.getElementById('hum-value');
        const railEl = document.getElementById('rail-value');

        const newCo2  = number(measurement.co2Ppm, 0);
        const newTemp = number(measurement.envTemp);
        const newHum  = number(measurement.envHum);
        const newRail = number(measurement.rail12v, 2);

        if (co2El.textContent  !== newCo2)  { co2El.textContent  = newCo2;  flash(co2El);  }
        if (tempEl.textContent !== newTemp)  { tempEl.textContent = newTemp; flash(tempEl); }
        if (humEl.textContent  !== newHum)   { humEl.textContent  = newHum;  flash(humEl);  }
        if (railEl.textContent !== newRail)  { railEl.textContent = newRail; flash(railEl); }

        lastUpdate.textContent = `SYNCED: ${new Date(measurement.recordedAt).toLocaleString()}`;
    }

    /* ── Measurements table ── */
    function renderMeasurements(measurements) {
        setLatest(measurements[0]);
        measurementsBody.innerHTML = measurements.map((row) => `
            <tr>
                <td>${new Date(row.recordedAt).toLocaleString()}</td>
                <td>${row.hubKey}</td>
                <td>${number(row.co2Ppm, 0)}</td>
                <td>${number(row.heaterTemp)}</td>
                <td>${number(row.envTemp)}</td>
                <td>${number(row.envHum)}</td>
                <td>${number(row.rail12v, 2)}</td>
            </tr>
        `).join('');
    }

    /* ── Alerts ── */
    function renderAlerts(alerts) {
        if (!alerts || alerts.length === 0) {
            alertsList.innerHTML = '<li class="nominal"><strong>System Nominal</strong><span>No active threshold violations.</span></li>';
            renderCalEvents([]);
            return;
        }
        alertsList.innerHTML = alerts.map((alert) => `
            <li class="${alert.level.toLowerCase()}">
                <strong>${alert.level} / ${alert.hubKey}</strong>
                <span>${alert.message}</span>
                <small>${new Date(alert.createdAt).toLocaleString()}</small>
            </li>
        `).join('');
        renderCalEvents(alerts);
    }

    /* ── Drift / calibration events feed (from SENSOR_DRIFT + CALIBRATION alerts) ── */
    function renderCalEvents(alerts) {
        const list = document.getElementById('cal-events');
        if (!list) return;
        const events = (alerts || []).filter(a =>
            a.ruleKey === 'SENSOR_DRIFT' || a.ruleKey === 'CALIBRATION');
        if (events.length === 0) {
            list.innerHTML = '<li class="cal-events-empty">Nessun evento di drift o calibrazione registrato.</li>';
            return;
        }
        list.innerHTML = events.slice(0, 8).map(e => `
            <li>
                <span class="ev-dot ${e.level === 'CRITICAL' ? 'critical' : (e.ruleKey === 'CALIBRATION' ? 'ok' : 'warn')}">●</span>
                <span class="ev-time">${new Date(e.createdAt).toLocaleString()}</span>
                <span class="ev-msg"><strong>${e.hubKey}</strong> — ${e.message}</span>
            </li>
        `).join('');
    }

    /* ── Sensor health + drift meter ── */
    const DRIFT_EOL_PCT    = 20; // soglia fine vita (allineata al backend)
    const DRIFT_ACTION_PCT = 5;  // zona d'azione (correzione possibile)

    function renderDriftMeter(driftPct) {
        const fill  = document.getElementById('drift-fill');
        const label = document.getElementById('drift-meter-value');
        if (!fill || !label) return;
        if (driftPct == null || !Number.isFinite(driftPct)) {
            fill.style.width = '0%';
            fill.className   = 'drift-fill';
            label.textContent = '--';
            return;
        }
        const abs = Math.abs(driftPct);
        const pct = Math.min(100, (abs / DRIFT_EOL_PCT) * 100);
        fill.style.width = pct.toFixed(1) + '%';
        fill.className   = 'drift-fill' +
            (abs >= DRIFT_EOL_PCT ? ' dangerzone' : (abs >= DRIFT_ACTION_PCT ? ' warnzone' : ''));
        label.textContent = `DRIFT ATTUALE: ${number(driftPct, 2)}%`;
    }

    function renderHealth(h) {
        const badge  = document.getElementById('health-status');
        const status = (h && h.status) ? h.status : 'UNKNOWN';
        badge.textContent = status;
        badge.className   = 'health-badge ' + status.toLowerCase();
        document.getElementById('health-drift').textContent = h && h.observedDriftPct != null ? number(h.observedDriftPct, 2) + ' %' : '--';
        document.getElementById('health-hours').textContent = h && h.operatingHours != null ? number(h.operatingHours, 1) : '--';
        document.getElementById('health-eol').textContent   = h && h.projectedEolAt ? new Date(h.projectedEolAt).toLocaleString() : '--';
        document.getElementById('health-resp').textContent  = h && h.lastResponse != null ? number(h.lastResponse, 4) : '--';
        renderDriftMeter(h && h.observedDriftPct != null ? h.observedDriftPct : null);
    }

    /* ── Chart ── */
    function buildGradient(ctx, color) {
        const h = ctx.canvas.clientHeight || 300;
        const g = ctx.createLinearGradient(0, 0, 0, h);
        const rgb = hexToRgb(color);
        g.addColorStop(0,   `rgba(${rgb},0.28)`);
        g.addColorStop(0.5, `rgba(${rgb},0.08)`);
        g.addColorStop(1,   `rgba(${rgb},0)`);
        return g;
    }

    function initChart() {
        const canvas = document.getElementById('history-chart');
        const ctx = canvas.getContext('2d');

        Chart.defaults.color       = '#3e5070';
        Chart.defaults.font.family = "'JetBrains Mono', monospace";
        Chart.defaults.font.size   = 11;

        historyChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Trend',
                    data: [],
                    borderColor: '#00c8ff',
                    backgroundColor: buildGradient(ctx, '#00c8ff'),
                    borderWidth: 1.8,
                    pointRadius: 0,
                    pointHoverRadius: 5,
                    pointBackgroundColor: '#00c8ff',
                    fill: true,
                    tension: 0.38
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 500, easing: 'easeInOutQuart' },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        mode: 'index',
                        intersect: false,
                        backgroundColor: 'rgba(2, 8, 18, 0.95)',
                        titleColor: '#3e5070',
                        bodyColor: '#00c8ff',
                        borderColor: 'rgba(0, 200, 255, 0.25)',
                        borderWidth: 1,
                        padding: 12,
                        titleFont: { size: 10, weight: '400' },
                        bodyFont:  { size: 13, weight: '500' },
                        cornerRadius: 8,
                        displayColors: false
                    }
                },
                scales: {
                    x: {
                        grid:  { color: 'rgba(0,200,255,0.045)', drawBorder: false },
                        ticks: { maxTicksLimit: 8, color: '#3e5070', maxRotation: 0 }
                    },
                    y: {
                        grid:  { color: 'rgba(0,200,255,0.045)', drawBorder: false },
                        ticks: { color: '#3e5070' }
                    }
                },
                interaction: { mode: 'nearest', axis: 'x', intersect: false }
            }
        });

        metricSelector.addEventListener('change', () => updateChart(cachedHistoryData));
        windowSelector.addEventListener('change', () => {
            selectedWindow = windowSelector.value;
            refreshHistory();
        });
    }

    function updateChart(historyData) {
        if (!historyChart) return;
        const metric       = metricSelector.value;
        const live         = isLiveWindow();
        const useTimeLabel = live || parseInt(selectedWindow, 10) <= 7;

        const labels = historyData.map(d => {
            const date = new Date(d.recordedAt);
            if (live) return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
            return useTimeLabel
                ? date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
                : date.toLocaleDateString();
        });
        const dataPoints = historyData.map(d => d[metric]);

        /* Color by metric */
        let color = '#00c8ff';
        if (metric.includes('Temp'))    color = '#ff4560';
        else if (metric === 'envHum')   color = '#10e88a';
        else if (metric === 'rail12v')  color = '#f5a623';

        const ctx = historyChart.canvas.getContext('2d');
        const pointRadius = (!live && dataPoints.length <= 10) ? 4 : 0;

        historyChart.data.labels                            = labels;
        historyChart.data.datasets[0].data                 = dataPoints;
        historyChart.data.datasets[0].borderColor          = color;
        historyChart.data.datasets[0].backgroundColor      = buildGradient(ctx, color);
        historyChart.data.datasets[0].pointBackgroundColor = color;
        historyChart.data.datasets[0].pointRadius          = pointRadius;
        historyChart.data.datasets[0].pointHoverRadius     = pointRadius > 0 ? pointRadius + 2 : 5;
        historyChart.options.plugins.tooltip.bodyColor      = color;
        historyChart.options.animation.duration             = live ? 0 : 500;
        historyChart.update();

        document.getElementById('chart-title').textContent = live
            ? 'Trend Live — misure in tempo reale'
            : `Trend Storico — ${WINDOW_LABELS[selectedWindow] || selectedWindow + ' giorni'}`;
    }

    /* Misure grezze (DESC dal server) → serie cronologica per il grafico live */
    function toLiveSeries(measurements) {
        return (measurements || []).slice(0, LIVE_MAX_POINTS).slice().reverse();
    }

    /* ── Lab & hubs ── */
    async function loadLabAndHubs() {
        try {
            const lab = await IncuSenseApi.lab();
            labBanner.textContent = `Lab: ${lab.displayName} (${lab.labId})`;
        } catch (e) { /* ignore */ }
        try {
            const hubs = await IncuSenseApi.hubs();
            hubSelector.innerHTML = '<option value="">All hubs</option>' +
                hubs.map(h => `<option value="${h.hubKey}">${h.hubKey}</option>`).join('');
        } catch (e) { /* ignore */ }
    }

    /* ── Health ── */
    async function refreshHealth() {
        if (!selectedHub) { renderHealth(null); return; }
        try { renderHealth(await IncuSenseApi.hubHealth(selectedHub)); } catch (e) { renderHealth(null); }
    }

    /* ── History only (called on window/metric change) ── */
    async function refreshHistory() {
        try {
            if (isLiveWindow()) {
                const measurements = selectedHub
                    ? await IncuSenseApi.measurementsForHub(selectedHub, LIVE_MAX_POINTS)
                    : await IncuSenseApi.latestMeasurements(LIVE_MAX_POINTS);
                cachedHistoryData = toLiveSeries(measurements);
            } else {
                const history = await IncuSenseApi.history(parseInt(selectedWindow, 10));
                cachedHistoryData = history || [];
            }
            updateChart(cachedHistoryData);
        } catch (e) { /* ignore */ }
    }

    /* ── Full data refresh (every 5s) ── */
    async function refreshData() {
        try {
            const live = isLiveWindow();
            const measurementsPromise = selectedHub
                ? IncuSenseApi.measurementsForHub(selectedHub, live ? LIVE_MAX_POINTS : 100)
                : IncuSenseApi.latestMeasurements(live ? LIVE_MAX_POINTS : 100);

            const [measurements, alerts, history] = await Promise.all([
                measurementsPromise,
                IncuSenseApi.alerts(),
                live ? Promise.resolve(null) : IncuSenseApi.history(parseInt(selectedWindow, 10))
            ]);

            renderMeasurements(measurements);
            renderAlerts(alerts);
            cachedHistoryData = live ? toLiveSeries(measurements) : (history || []);
            updateChart(cachedHistoryData);
            await refreshHealth();
        } catch (error) {
            if (String(error.message).includes('401') || String(error.message).includes('403')) {
                IncuSenseApi.logout();
                return;
            }
            lastUpdate.textContent = 'ERR: ' + error.message;
        }
    }

    hubSelector.addEventListener('change', () => { selectedHub = hubSelector.value; refreshData(); });

    /* ── STOMP hooks (consumed by realtime.js) ── */
    window.IncuSenseDash = {
        currentHub: () => selectedHub,
        onMeasurement(m) {
            if (selectedHub && m.hubKey !== selectedHub) return;
            setLatest(m);
            // In modalità live il punto entra subito nel grafico (senza attendere il poll)
            if (isLiveWindow()) {
                cachedHistoryData.push(m);
                if (cachedHistoryData.length > LIVE_MAX_POINTS) {
                    cachedHistoryData = cachedHistoryData.slice(-LIVE_MAX_POINTS);
                }
                updateChart(cachedHistoryData);
            }
        },
        onAlert()  { IncuSenseApi.alerts().then(renderAlerts).catch(() => {}); },
        onHealth(h){ if (!selectedHub || h.hubKey === selectedHub) renderHealth(h); },
        setLive(isLive) {
            liveBadge.className = 'live-badge ' + (isLive ? 'online' : 'offline');
            liveBadge.innerHTML = `<span class="live-dot"></span>${isLive ? 'LIVE' : 'OFFLINE'}`;
        }
    };

    /* ── Boot ── */
    initChart();
    loadLabAndHubs().then(refreshData);
    setInterval(refreshData, 5000);
})();
