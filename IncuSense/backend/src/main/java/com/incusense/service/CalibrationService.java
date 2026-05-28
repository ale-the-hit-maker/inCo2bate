package com.incusense.service;

import com.incusense.dto.Dtos;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CalibrationService {

    private final Map<String, Dtos.CalibrationResponse> calibrations = new ConcurrentHashMap<>();

    public Dtos.MeasurementPayload apply(String hubKey, Dtos.MeasurementPayload payload) {
        Dtos.CalibrationResponse calibration = getCalibration(hubKey);
        return new Dtos.MeasurementPayload(
                payload.ts(),
                payload.co2Ppm() + calibration.co2Offset(),
                payload.heaterTemp() + calibration.temperatureOffset(),
                payload.envTemp() + calibration.temperatureOffset(),
                payload.envHum() + calibration.humidityOffset(),
                payload.rail12v() + calibration.rail12vOffset());
    }

    public Dtos.CalibrationResponse getCalibration(String hubKey) {
        return calibrations.computeIfAbsent(hubKey, key -> new Dtos.CalibrationResponse(key, 0, 0, 0, 0));
    }

    public Dtos.CalibrationResponse updateCalibration(String hubKey, Dtos.CalibrationRequest request) {
        Dtos.CalibrationResponse calibration = new Dtos.CalibrationResponse(
                hubKey,
                request.co2Offset(),
                request.temperatureOffset(),
                request.humidityOffset(),
                request.rail12vOffset());
        calibrations.put(hubKey, calibration);
        return calibration;
    }
}
