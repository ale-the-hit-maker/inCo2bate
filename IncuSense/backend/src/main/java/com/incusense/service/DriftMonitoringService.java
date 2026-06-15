package com.incusense.service;

import com.incusense.dto.Dtos;
import com.incusense.model.Measurement;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import com.incusense.repository.Repositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Physics-grounded, self-referenced sensor drift monitor (see UPGRADE_SPEC_v3.md §8).
 * v1: tracks baseline drift of the published sensor_response against an install baseline,
 * accumulates operating hours, projects EOL by linear extrapolation, and raises a
 * SENSOR_DRIFT predictive-maintenance alert. T/H correction is a no-op hook (coeff = 0).
 */
@Service
public class DriftMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(DriftMonitoringService.class);

    /** End-of-life threshold on absolute baseline drift (percent). */
    private static final double EOL_DRIFT_PCT = 20.0;
    /** Skip drift maths until the sensor has been operating long enough (warm-up guard). */
    private static final double WARMUP_HOURS = 0.1;
    /** Cap per-sample accumulated time to avoid counting downtime as operating hours. */
    private static final long MAX_DELTA_SECONDS = 3600L;

    // T/H correction coefficients (D4): disabled in v1 (Na:In2O3 humidity-insensitive, controlled incubator).
    private static final double K_TEMP = 0.0;
    private static final double K_HUM = 0.0;
    private static final double REF_TEMP_C = 250.0; // sensor operating point
    private static final double REF_HUM_PCT = 0.0;

    private final Repositories.SensorHealthRepository healthRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AlertService alertService;
    private final AutoCalibrationService autoCalibrationService;
    private final int degradingEolDays;

    public DriftMonitoringService(Repositories.SensorHealthRepository healthRepository,
                                  SimpMessagingTemplate messagingTemplate,
                                  AlertService alertService,
                                  AutoCalibrationService autoCalibrationService,
                                  @Value("${incusense.drift.degrading-eol-days:7}") int degradingEolDays) {
        this.healthRepository = healthRepository;
        this.messagingTemplate = messagingTemplate;
        this.alertService = alertService;
        this.autoCalibrationService = autoCalibrationService;
        this.degradingEolDays = degradingEolDays;
    }

    public void update(SensingHub hub, Measurement measurement) {
        Double sensorResponse = measurement.getSensorResponse();
        if (sensorResponse == null) {
            return; // old firmware payload without drift fields
        }

        SensorHealth health = healthRepository.findById(hub.getId())
                .orElseGet(() -> new SensorHealth(hub));

        Instant now = Instant.now();

        // 1) accumulate operating hours (capped delta)
        if (health.getUpdatedAt() != null) {
            long delta = Math.min(MAX_DELTA_SECONDS, Math.max(0, Duration.between(health.getUpdatedAt(), now).getSeconds()));
            health.setOperatingHours(health.getOperatingHours() + delta / 3600.0);
        }

        // 2) T/H correction hook (no-op in v1)
        double respCorr = sensorResponse
                * (1 + K_TEMP * (REF_TEMP_C - measurement.getEnvTemp()) + K_HUM * (REF_HUM_PCT - measurement.getEnvHum()));
        health.setLastResponse(respCorr);

        // 3) install baseline
        if (health.getInstallResponse() == null && respCorr > 0) {
            health.setInstallResponse(respCorr);
        }

        // warm-up guard: until enough operating time, just persist baseline/hours
        if (health.getOperatingHours() <= WARMUP_HOURS || health.getInstallResponse() == null
                || health.getInstallResponse() == 0.0) {
            persistAndBroadcast(hub, health);
            autoCalibrationService.evaluate(hub, health, measurement); // accumula EWMA, nessuna azione in warm-up
            return;
        }

        // 4) observed drift
        double install = health.getInstallResponse();
        double driftPct = (respCorr - install) / install * 100.0;
        health.setObservedDriftPct(driftPct);
        double absDrift = Math.abs(driftPct);

        // 5) EOL projection by linear extrapolation
        String status = "OK";
        health.setProjectedEolAt(null);
        if (absDrift >= EOL_DRIFT_PCT) {
            status = "CRITICAL";
            health.setProjectedEolAt(now);
        } else if (health.getOperatingHours() > 0) {
            double ratePerHour = absDrift / health.getOperatingHours(); // %/h
            if (ratePerHour > 0) {
                double hoursToEol = (EOL_DRIFT_PCT - absDrift) / ratePerHour;
                Instant eol = now.plusSeconds((long) (hoursToEol * 3600));
                health.setProjectedEolAt(eol);
                if (Duration.between(now, eol).toDays() <= degradingEolDays) {
                    status = "DEGRADING";
                }
            }
        }
        health.setHealthStatus(status);

        persistAndBroadcast(hub, health);

        // 6) predictive-maintenance alert
        if ("CRITICAL".equals(status)) {
            alertService.emitDrift(hub, "CRITICAL",
                    String.format("Sensor drift %.1f%% exceeds EOL threshold (%.0f%%)", driftPct, EOL_DRIFT_PCT))
                    .ifPresent(a -> broadcastAlert(hub, a));
        } else if ("DEGRADING".equals(status)) {
            alertService.emitDrift(hub, "WARN",
                    String.format("Sensor drift %.1f%%; projected end-of-life within %d days", driftPct, degradingEolDays))
                    .ifPresent(a -> broadcastAlert(hub, a));
        }

        // 7) auto-calibrazione semi-real-time (no-op se disabilitata): valuta il campione
        //    per un'eventuale correzione closed-loop verso il nodo (vedi AutoCalibrationService).
        autoCalibrationService.evaluate(hub, health, measurement);
    }

    private void persistAndBroadcast(SensingHub hub, SensorHealth health) {
        healthRepository.save(health);
        if (hub.getLab() != null) {
            messagingTemplate.convertAndSend("/topic/health/" + hub.getLab().getLabId(), Dtos.HealthResponse.from(health));
        }
    }

    private void broadcastAlert(SensingHub hub, Dtos.AlertResponse alert) {
        if (hub.getLab() != null) {
            messagingTemplate.convertAndSend("/topic/alerts/" + hub.getLab().getLabId(), alert);
        }
        log.info("[DRIFT] {} -> {} ({})", hub.getHubKey(), alert.level(), alert.message());
    }
}
