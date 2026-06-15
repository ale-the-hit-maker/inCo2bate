package com.incusense.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.incusense.model.Alert;
import com.incusense.model.CalibrationEvent;
import com.incusense.model.Lab;
import com.incusense.model.Measurement;
import com.incusense.model.NotificationContact;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class Dtos {

    private Dtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RegisterRequest(
            @NotBlank String username,
            @NotBlank String email,
            @NotBlank String password,
            String labDisplayName,
            String existingLabId) {
    }

    public record AuthResponse(String token, String tokenType, String username, String labId) {
    }

    public record LabResponse(String labId, String displayName) {
        public static LabResponse from(Lab lab) {
            return new LabResponse(lab.getLabId(), lab.getDisplayName());
        }
    }

    public record MeasurementPayload(
            long ts,
            @JsonProperty("co2_ppm") double co2Ppm,
            @JsonProperty("heater_temp") double heaterTemp,
            @JsonProperty("env_temp") double envTemp,
            @JsonProperty("env_hum") double envHum,
            @JsonProperty("rail_12v") double rail12v,
            @JsonProperty("raw_adc") Integer rawAdc,
            @JsonProperty("sensor_response") Double sensorResponse) {
    }

    public record MeasurementResponse(
            String hubKey,
            Instant recordedAt,
            double co2Ppm,
            double heaterTemp,
            double envTemp,
            double envHum,
            double rail12v,
            Integer rawAdc,
            Double sensorResponse) {

        public static MeasurementResponse from(Measurement measurement) {
            return new MeasurementResponse(
                    measurement.getHub().getHubKey(),
                    measurement.getRecordedAt(),
                    measurement.getCo2Ppm(),
                    measurement.getHeaterTemp(),
                    measurement.getEnvTemp(),
                    measurement.getEnvHum(),
                    measurement.getRail12v(),
                    measurement.getRawAdc(),
                    measurement.getSensorResponse());
        }
    }

    public record HubResponse(Long id, String hubKey, String displayName, Instant createdAt, Instant updatedAt) {

        public static HubResponse from(SensingHub hub) {
            return new HubResponse(hub.getId(), hub.getHubKey(), hub.getDisplayName(), hub.getCreatedAt(), hub.getUpdatedAt());
        }
    }

    public record CalibrationRequest(double co2Offset, double temperatureOffset, double humidityOffset, double rail12vOffset) {
    }

    public record CalibrationResponse(String hubKey, double co2Offset, double temperatureOffset,
                                      double humidityOffset, double rail12vOffset) {
    }

    public record HistoryResponse(
            Instant recordedAt,
            double co2Ppm,
            double heaterTemp,
            double envTemp,
            double envHum,
            double rail12v) {
    }

    public record AlertResponse(String hubKey, String level, String ruleKey, String message, Instant createdAt) {
        public static AlertResponse from(Alert alert) {
            return new AlertResponse(alert.getHub().getHubKey(), alert.getLevel(), alert.getRuleKey(),
                    alert.getMessage(), alert.getCreatedAt());
        }
    }

    public record ContactRequest(
            @NotBlank String label,
            String channel,
            @NotBlank String target,
            String minLevel,
            boolean enabled) {
    }

    public record ContactResponse(Long id, String label, String channel, String target, String minLevel, boolean enabled) {
        public static ContactResponse from(NotificationContact c) {
            return new ContactResponse(c.getId(), c.getLabel(), c.getChannel(), c.getTarget(), c.getMinLevel(), c.isEnabled());
        }
    }

    public record HealthResponse(
            String hubKey,
            String status,
            Double observedDriftPct,
            Double operatingHours,
            Double installResponse,
            Double lastResponse,
            Instant projectedEolAt,
            Long curveId) {
        public static HealthResponse from(SensorHealth h) {
            return new HealthResponse(
                    h.getHub().getHubKey(),
                    h.getHealthStatus(),
                    h.getObservedDriftPct(),
                    h.getOperatingHours(),
                    h.getInstallResponse(),
                    h.getLastResponse(),
                    h.getProjectedEolAt(),
                    h.getCurve() != null ? h.getCurve().getId() : null);
        }
    }

    public record DriftCurveRequest(
            @NotBlank String sensorType,
            Double refVoltageV,
            Double refTempC,
            String description,
            @NotBlank String pointsJson) {
    }

    public record DriftCurveResponse(Long id, String sensorType, Double refVoltageV, Double refTempC,
                                     String description, String pointsJson) {
    }

    public record AssignCurveRequest(Long curveId, Double installResponse) {
    }

    public record CalibrationEventResponse(
            Long id,
            String hubKey,
            String command,
            String status,
            Double requestedOffsetPpm,
            Double driftPctAtRequest,
            String reason,
            Instant createdAt,
            Instant ackedAt,
            Instant verifiedAt) {
        public static CalibrationEventResponse from(CalibrationEvent e) {
            return new CalibrationEventResponse(
                    e.getId(),
                    e.getHub().getHubKey(),
                    e.getCommand(),
                    e.getStatus(),
                    e.getRequestedOffsetPpm(),
                    e.getDriftPctAtRequest(),
                    e.getReason(),
                    e.getCreatedAt(),
                    e.getAckedAt(),
                    e.getVerifiedAt());
        }
    }
}
