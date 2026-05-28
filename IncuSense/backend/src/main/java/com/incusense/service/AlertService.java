package com.incusense.service;

import com.incusense.dto.Dtos;
import com.incusense.model.Measurement;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Service
public class AlertService {

    private static final int MAX_ALERTS = 100;
    private final Deque<Dtos.AlertResponse> recentAlerts = new ArrayDeque<>();

    public synchronized List<Dtos.AlertResponse> evaluate(Measurement measurement) {
        List<Dtos.AlertResponse> alerts = new ArrayList<>();
        if (measurement.getCo2Ppm() < 30000 || measurement.getCo2Ppm() > 70000) {
            alerts.add(alert(measurement, "WARN", "CO2 outside expected incubator range"));
        }
        if (measurement.getEnvTemp() < 35.5 || measurement.getEnvTemp() > 38.5) {
            alerts.add(alert(measurement, "WARN", "Environment temperature outside target range"));
        }
        if (measurement.getEnvHum() < 80 || measurement.getEnvHum() > 99.5) {
            alerts.add(alert(measurement, "INFO", "Environment humidity outside preferred range"));
        }
        if (measurement.getRail12v() < 11.5 || measurement.getRail12v() > 12.6) {
            alerts.add(alert(measurement, "CRITICAL", "12V rail voltage outside tolerance"));
        }
        alerts.forEach(this::remember);
        return alerts;
    }

    public synchronized List<Dtos.AlertResponse> recentAlerts() {
        return List.copyOf(recentAlerts);
    }

    private Dtos.AlertResponse alert(Measurement measurement, String level, String message) {
        return new Dtos.AlertResponse(measurement.getHub().getHubKey(), level, message, Instant.now());
    }

    private void remember(Dtos.AlertResponse alert) {
        recentAlerts.addFirst(alert);
        while (recentAlerts.size() > MAX_ALERTS) {
            recentAlerts.removeLast();
        }
    }
}
