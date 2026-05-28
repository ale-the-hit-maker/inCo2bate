package com.incusense.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incusense.dto.Dtos;
import com.incusense.model.Measurement;
import com.incusense.model.SensingHub;
import com.incusense.repository.Repositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MqttIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestionService.class);
    private static final Pattern HUB_TOPIC = Pattern.compile("incusense/hubs/([^/]+)/telemetry");

    private final ObjectMapper objectMapper;
    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.MeasurementRepository measurementRepository;
    private final CalibrationService calibrationService;
    private final AlertService alertService;
    private final SimpMessagingTemplate messagingTemplate;

    public MqttIngestionService(ObjectMapper objectMapper,
                                Repositories.SensingHubRepository hubRepository,
                                Repositories.MeasurementRepository measurementRepository,
                                CalibrationService calibrationService,
                                AlertService alertService,
                                SimpMessagingTemplate messagingTemplate) {
        this.objectMapper = objectMapper;
        this.hubRepository = hubRepository;
        this.measurementRepository = measurementRepository;
        this.calibrationService = calibrationService;
        this.alertService = alertService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void ingest(String topic, String rawPayload) {
        try {
            String hubKey = hubKeyFromTopic(topic);
            Dtos.MeasurementPayload payload = objectMapper.readValue(rawPayload, Dtos.MeasurementPayload.class);
            Dtos.MeasurementPayload calibrated = calibrationService.apply(hubKey, payload);

            SensingHub hub = hubRepository.findByHubKey(hubKey)
                    .orElseGet(() -> hubRepository.save(new SensingHub(hubKey, hubKey)));

            Measurement measurement = measurementRepository.save(new Measurement(
                    hub,
                    Instant.ofEpochSecond(calibrated.ts()),
                    calibrated.co2Ppm(),
                    calibrated.heaterTemp(),
                    calibrated.envTemp(),
                    calibrated.envHum(),
                    calibrated.rail12v()));

            Dtos.MeasurementResponse response = Dtos.MeasurementResponse.from(measurement);
            messagingTemplate.convertAndSend("/topic/measurements", response);
            alertService.evaluate(measurement).forEach(alert -> messagingTemplate.convertAndSend("/topic/alerts", alert));
        } catch (Exception ex) {
            log.warn("Unable to ingest MQTT telemetry from topic {} with payload {}", topic, rawPayload, ex);
        }
    }

    private String hubKeyFromTopic(String topic) {
        Matcher matcher = HUB_TOPIC.matcher(topic == null ? "" : topic);
        return matcher.matches() ? matcher.group(1) : "default-hub";
    }
}
