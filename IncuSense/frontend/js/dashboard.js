(function () {
    IncuSenseApi.requireAuth();

    const measurementsBody = document.getElementById('measurements-body');
    const alertsList = document.getElementById('alerts-list');
    const lastUpdate = document.getElementById('last-update');
    const metricSelector = document.getElementById('chart-metric-selector');

    let historyChart = null;
    let cachedHistoryData = [];

    document.getElementById('logout-button').addEventListener('click', () => IncuSenseApi.logout());

    function number(value, digits = 1) {
        return Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: digits }) : '--';
    }

    function setLatest(measurement) {
        if (!measurement) return;
        document.getElementById('co2-value').textContent = number(measurement.co2Ppm, 0);
        document.getElementById('temp-value').textContent = number(measurement.envTemp);
        document.getElementById('hum-value').textContent = number(measurement.envHum);
        document.getElementById('rail-value').textContent = number(measurement.rail12v, 2);
        lastUpdate.textContent = `SYNCED: ${new Date(measurement.recordedAt).toLocaleString()}`;
    }

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

    function renderAlerts(alerts) {
        if (!alerts || alerts.length === 0) {
            alertsList.innerHTML = '<li class="nominal"><strong>System Nominal</strong><span>No active threshold violations.</span></li>';
            return;
        }
        alertsList.innerHTML = alerts.map((alert) => `
            <li class="${alert.level.toLowerCase()}">
                <strong>${alert.level} / ${alert.hubKey}</strong>
                <span>${alert.message}</span>
                <small>${new Date(alert.createdAt).toLocaleString()}</small>
            </li>
        `).join('');
    }

    function initChart() {
        const ctx = document.getElementById('history-chart').getContext('2d');
        
        Chart.defaults.color = '#8b949e';
        Chart.defaults.font.family = 'Inter, sans-serif';

        historyChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Trend',
                    data: [],
                    borderColor: '#00e5ff',
                    backgroundColor: 'rgba(0, 229, 255, 0.1)',
                    borderWidth: 2,
                    pointRadius: 0,
                    pointHoverRadius: 6,
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        mode: 'index',
                        intersect: false,
                        backgroundColor: '#0d111c',
                        titleColor: '#e2e8f0',
                        bodyColor: '#00e5ff',
                        borderColor: '#1e2433',
                        borderWidth: 1
                    }
                },
                scales: {
                    x: {
                        grid: { color: '#1e2433', drawBorder: false },
                        ticks: { maxTicksLimit: 10 }
                    },
                    y: {
                        grid: { color: '#1e2433', drawBorder: false }
                    }
                },
                interaction: {
                    mode: 'nearest',
                    axis: 'x',
                    intersect: false
                }
            }
        });

        metricSelector.addEventListener('change', () => {
            updateChart(cachedHistoryData);
        });
    }

    function updateChart(historyData) {
        if (!historyChart || !historyData.length) return;
        
        const metric = metricSelector.value;
        const labels = historyData.map(d => new Date(d.recordedAt).toLocaleDateString());
        const dataPoints = historyData.map(d => d[metric]);

        let color = '#00e5ff';
        let bgColor = 'rgba(0, 229, 255, 0.1)';
        
        if (metric.includes('Temp')) {
            color = '#ff3d71';
            bgColor = 'rgba(255, 61, 113, 0.1)';
        } else if (metric === 'envHum') {
            color = '#00e676';
            bgColor = 'rgba(0, 230, 118, 0.1)';
        } else if (metric === 'rail12v') {
            color = '#ffab00';
            bgColor = 'rgba(255, 171, 0, 0.1)';
        }

        historyChart.data.labels = labels;
        historyChart.data.datasets[0].data = dataPoints;
        historyChart.data.datasets[0].borderColor = color;
        historyChart.data.datasets[0].backgroundColor = bgColor;
        historyChart.options.plugins.tooltip.bodyColor = color;
        
        historyChart.update();
    }

    async function refreshData() {
        try {
            const [measurements, alerts, history] = await Promise.all([
                IncuSenseApi.latestMeasurements(100),
                IncuSenseApi.alerts(),
                IncuSenseApi.history()
            ]);
            
            renderMeasurements(measurements);
            renderAlerts(alerts);
            
            if (history && history.length > 0) {
                cachedHistoryData = history;
                updateChart(cachedHistoryData);
            }
        } catch (error) {
            if (String(error.message).includes('401') || String(error.message).includes('403')) {
                IncuSenseApi.logout();
                return;
            }
            lastUpdate.textContent = "ERR: " + error.message;
        }
    }

    initChart();
    refreshData();
    setInterval(refreshData, 5000);
})();