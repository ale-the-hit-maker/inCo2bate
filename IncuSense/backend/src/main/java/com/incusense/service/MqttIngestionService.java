package com.incusense.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incusense.dto.Dtos;
import com.incusense.model.Lab;
import com.incusense.model.Measurement;
import com.incusense.model.SensingHub;
import com.incusense.repository.Repositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MqttIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestionService.class);
    private static final Pattern TELEMETRY_TOPIC =
            Pattern.compile("^incusense/labs/([^/]+)/hubs/([^/]+)/telemetry$");
    private static final Pattern STATUS_TOPIC =
            Pattern.compile("^incusense/labs/([^/]+)/hubs/([^/]+)/status$");
    private static final Pattern ACK_TOPIC =
            Pattern.compile("^incusense/labs/([^/]+)/hubs/([^/]+)/ack$");

    private final ObjectMapper objectMapper;
    private final Repositories.LabRepository labRepository;
    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.MeasurementRepository measurementRepository;
    private final CalibrationService calibrationService;
    private final AlertService alertService;
    private final DriftMonitoringService driftMonitoringService;
    private final AutoCalibrationService autoCalibrationService;
    private final SimpMessagingTemplate messagingTemplate;

    public MqttIngestionService(ObjectMapper objectMapper,
                                Repositories.LabRepository labRepository,
                                Repositories.SensingHubRepository hubRepository,
                                Repositories.MeasurementRepository measurementRepository,
                                CalibrationService calibrationService,
                                AlertService alertService,
                                DriftMonitoringService driftMonitoringService,
                                AutoCalibrationService autoCalibrationService,
                                SimpMessagingTemplate messagingTemplate) {
        this.objectMapper = objectMapper;
        this.labRepository = labRepository;
        this.hubRepository = hubRepository;
        this.measurementRepository = measurementRepository;
        this.calibrationService = calibrationService;
        this.alertService = alertService;
        this.driftMonitoringService = driftMonitoringService;
        this.autoCalibrationService = autoCalibrationService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void ingest(String topic, String rawPayload) {
        Matcher telemetry = TELEMETRY_TOPIC.matcher(topic == null ? "" : topic);
        if (telemetry.matches()) {
            ingestTelemetry(telemetry.group(1), telemetry.group(2), rawPayload);
            return;
        }
        Matcher status = STATUS_TOPIC.matcher(topic == null ? "" : topic);
        if (status.matches()) {
            log.debug("[MQTT] status from lab={} hub={}: {}", status.group(1), status.group(2), rawPayload);
            return;
        }
        Matcher ack = ACK_TOPIC.matcher(topic == null ? "" : topic);
        if (ack.matches()) {
            ingestAck(ack.group(1), ack.group(2), rawPayload);
            return;
        }
        log.warn("[MQTT] ignoring message on unrecognized topic: {}", topic);
    }

    /**
     * ACK di calibrazione dal nodo: {@code {"hub_id","command","status","event_id","ts"}}.
     * Correla l'evento via {@code event_id} e aggiorna il lifecycle (AutoCalibrationService).
     */
    private void ingestAck(String labId, String hubKey, String rawPayload) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = objectMapper.readTree(rawPayload);
            if (!n.hasNonNull("event_id")) {
                log.debug("[MQTT] ack senza event_id lab={} hub={}: {}", labId, hubKey, rawPayload);
                return;
            }
            long eventId = n.get("event_id").asLong();
            String status = n.hasNonNull("status") ? n.get("status").asText() : "OK";
            autoCalibrationService.handleAck(eventId, status);
        } catch (Exception ex) {
            log.warn("[MQTT] ack non interpretabile lab={} hub={} payload={}", labId, hubKey, rawPayload, ex);
        }
    }

    private void ingestTelemetry(String labId, String hubKey, String rawPayload) {
        try {
            Optional<Lab> labOpt = labRepository.findByLabId(labId);
            if (labOpt.isEmpty()) {
                log.warn("[MQTT] dropping telemetry for unknown lab '{}' (hub '{}')", labId, hubKey);
                return;
            }
            Lab lab = labOpt.get();

            Dtos.MeasurementPayload payload = objectMapper.readValue(rawPayload, Dtos.MeasurementPayload.class);
            Dtos.MeasurementPayload calibrated = calibrationService.apply(hubKey, payload);

            SensingHub hub = hubRepository.findByHubKeyAndLab_LabId(hubKey, labId)
                    .orElseGet(() -> hubRepository.save(new SensingHub(hubKey, hubKey, lab)));

            Measurement measurement = measurementRepository.save(new Measurement(
                    hub,
                    Instant.ofEpochSecond(calibrated.ts()),
                    calibrated.co2Ppm(),
                    calibrated.heaterTemp(),
                    calibrated.envTemp(),
                    calibrated.envHum(),
                    calibrated.rail12v(),
                    payload.rawAdc(),
                    payload.sensorResponse()));

            Dtos.MeasurementResponse response = Dtos.MeasurementResponse.from(measurement);
            messagingTemplate.convertAndSend("/topic/measurements/" + labId, response);

            alertService.evaluate(measurement)
                    .forEach(alert -> messagingTemplate.convertAndSend("/topic/alerts/" + labId, alert));

            driftMonitoringService.update(hub, measurement);
        } catch (Exception ex) {
            log.warn("[MQTT] unable to ingest telemetry lab={} hub={} payload={}", labId, hubKey, rawPayload, ex);
        }
    }
}
