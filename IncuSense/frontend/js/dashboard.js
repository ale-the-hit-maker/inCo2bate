(function () {
    IncuSenseApi.requireAuth();

    const measurementsBody = document.getElementById('measurements-body');
    const alertsList = document.getElementById('alerts-list');
    const lastUpdate = document.getElementById('last-update');

    document.getElementById('logout-button').addEventListener('click', () => IncuSenseApi.logout());

    function number(value, digits = 1) {
        return Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: digits }) : '--';
    }

    function setLatest(measurement) {
        if (!measurement) {
            return;
        }
        document.getElementById('co2-value').textContent = number(measurement.co2Ppm, 0);
        document.getElementById('temp-value').textContent = number(measurement.envTemp);
        document.getElementById('hum-value').textContent = number(measurement.envHum);
        document.getElementById('rail-value').textContent = number(measurement.rail12v, 2);
        lastUpdate.textContent = `Last update ${new Date(measurement.recordedAt).toLocaleString()}`;
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
        if (!alerts.length) {
            alertsList.innerHTML = '<li><strong>Nominal</strong>No active threshold alerts.</li>';
            return;
        }
        alertsList.innerHTML = alerts.map((alert) => `
            <li class="${alert.level.toLowerCase()}">
                <strong>${alert.level} · ${alert.hubKey}</strong>
                <span>${alert.message}</span>
                <small>${new Date(alert.createdAt).toLocaleString()}</small>
            </li>
        `).join('');
    }

    async function refresh() {
        try {
            const [measurements, alerts] = await Promise.all([
                IncuSenseApi.latestMeasurements(100),
                IncuSenseApi.alerts()
            ]);
            renderMeasurements(measurements);
            renderAlerts(alerts);
        } catch (error) {
            if (String(error.message).includes('401') || String(error.message).includes('403')) {
                IncuSenseApi.logout();
                return;
            }
            lastUpdate.textContent = error.message;
        }
    }

    refresh();
    setInterval(refresh, 5000);
})();
