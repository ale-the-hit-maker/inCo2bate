package com.incusense.service;

import com.incusense.dto.Dtos;
import com.incusense.model.Alert;
import com.incusense.model.Measurement;
import com.incusense.model.NotificationContact;
import com.incusense.model.SensingHub;
import com.incusense.repository.Repositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlertService {

    private static final long DRIFT_COOLDOWN_SECONDS = 24 * 3600L;
    private static final Map<String, Integer> SEVERITY = Map.of("INFO", 0, "WARN", 1, "CRITICAL", 2);

    private final Repositories.AlertRepository alertRepository;
    private final Repositories.NotificationContactRepository contactRepository;
    private final List<AlertNotifier> notifiers;
    private final long cooldownSeconds;

    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();

    public AlertService(Repositories.AlertRepository alertRepository,
                        Repositories.NotificationContactRepository contactRepository,
                        List<AlertNotifier> notifiers,
                        @Value("${incusense.alerting.cooldown-seconds:300}") long cooldownSeconds) {
        this.alertRepository = alertRepository;
        this.contactRepository = contactRepository;
        this.notifiers = notifiers;
        this.cooldownSeconds = cooldownSeconds;
    }

    public List<Dtos.AlertResponse> evaluate(Measurement measurement) {
        List<Dtos.AlertResponse> fired = new ArrayList<>();
        if (measurement.getCo2Ppm() < 30000 || measurement.getCo2Ppm() > 70000) {
            fire(measurement.getHub(), "WARN", "CO2_RANGE", "CO2 outside expected incubator range", cooldownSeconds).ifPresent(fired::add);
        }
        if (measurement.getEnvTemp() < 35.5 || measurement.getEnvTemp() > 38.5) {
            fire(measurement.getHub(), "WARN", "ENV_TEMP", "Environment temperature outside target range", cooldownSeconds).ifPresent(fired::add);
        }
        if (measurement.getEnvHum() < 80 || measurement.getEnvHum() > 99.5) {
            fire(measurement.getHub(), "INFO", "ENV_HUM", "Environment humidity outside preferred range", cooldownSeconds).ifPresent(fired::add);
        }
        if (measurement.getRail12v() < 11.5 || measurement.getRail12v() > 12.6) {
            fire(measurement.getHub(), "CRITICAL", "RAIL_12V", "12V rail voltage outside tolerance", cooldownSeconds).ifPresent(fired::add);
        }
        return fired;
    }

    /** Used by the drift monitor for predictive-maintenance alerts (long cooldown). */
    public java.util.Optional<Dtos.AlertResponse> emitDrift(SensingHub hub, String level, String message) {
        return fire(hub, level, "SENSOR_DRIFT", message, DRIFT_COOLDOWN_SECONDS);
    }

    public List<Dtos.AlertResponse> recentAlerts(String labId) {
        return alertRepository.findByHub_Lab_LabIdOrderByCreatedAtDesc(labId, PageRequest.of(0, 100))
                .stream()
                .map(Dtos.AlertResponse::from)
                .toList();
    }

    private java.util.Optional<Dtos.AlertResponse> fire(SensingHub hub, String level, String ruleKey,
                                                        String message, long cooldown) {
        String key = hub.getId() + ":" + ruleKey;
        Instant now = Instant.now();
        Instant last = lastFired.get(key);
        if (last != null && Duration.between(last, now).getSeconds() < cooldown) {
            return java.util.Optional.empty();
        }
        lastFired.put(key, now);

        Alert alert = alertRepository.save(new Alert(hub, level, ruleKey, message));
        dispatch(alert);
        return java.util.Optional.of(Dtos.AlertResponse.from(alert));
    }

    private void dispatch(Alert alert) {
        String labId = alert.getHub().getLab() != null ? alert.getHub().getLab().getLabId() : null;
        if (labId == null) {
            return;
        }
        int alertSeverity = SEVERITY.getOrDefault(alert.getLevel(), 1);
        List<NotificationContact> contacts = contactRepository.findByLab_LabIdAndEnabledTrue(labId);
        for (NotificationContact contact : contacts) {
            int minSeverity = SEVERITY.getOrDefault(contact.getMinLevel(), 1);
            if (alertSeverity < minSeverity) {
                continue;
            }
            for (AlertNotifier notifier : notifiers) {
                if (notifier.channel().equalsIgnoreCase(contact.getChannel())) {
                    notifier.notify(contact, alert);
                }
            }
        }
    }
}
