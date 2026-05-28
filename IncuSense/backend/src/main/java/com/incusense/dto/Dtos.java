package com.incusense.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.incusense.model.Measurement;
import com.incusense.model.SensingHub;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class Dtos {

    private Dtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record AuthResponse(String token, String tokenType, String username) {
    }

    public record MeasurementPayload(
            long ts,
            @JsonProperty("co2_ppm") double co2Ppm,
            @JsonProperty("heater_temp") double heaterTemp,
            @JsonProperty("env_temp") double envTemp,
            @JsonProperty("env_hum") double envHum,
            @JsonProperty("rail_12v") double rail12v) {
    }

    public record MeasurementResponse(
            String hubKey,
            Instant recordedAt,
            double co2Ppm,
            double heaterTemp,
            double envTemp,
            double envHum,
            double rail12v) {

        public static MeasurementResponse from(Measurement measurement) {
            return new MeasurementResponse(
                    measurement.getHub().getHubKey(),
                    measurement.getRecordedAt(),
                    measurement.getCo2Ppm(),
                    measurement.getHeaterTemp(),
                    measurement.getEnvTemp(),
                    measurement.getEnvHum(),
                    measurement.getRail12v());
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

    public record AlertResponse(String hubKey, String level, String message, Instant createdAt) {
    }
}
