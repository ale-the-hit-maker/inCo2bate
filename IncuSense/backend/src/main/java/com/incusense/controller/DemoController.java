package com.incusense.controller;

import com.incusense.model.Lab;
import com.incusense.model.Measurement;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import com.incusense.service.MqttIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Pitch-day demo module — completamente SEPARATO dal flusso normale.
 *
 * Inietta una telemetria sintetica nel medesimo percorso di ingestione reale
 * ({@link MqttIngestionService#ingest}), così che lo scenario scelto faccia
 * scattare DAVVERO la regola di alert corrispondente, l'invio email reale e
 * l'aggiornamento live della dashboard (WebSocket), esattamente come un nodo fisico.
 *
 * Non modifica né rimuove alcuna funzionalità: è un endpoint aggiuntivo,
 * disattivabile con {@code incusense.demo.enabled=false} (es. in produzione).
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Baseline firmware del sensor_response a regime al setpoint (ADC aria 1380, ADC 50k 2115). */
    private static final double DEMO_BASELINE_RESPONSE = 0.530;
    /** Drift demo: +12% sul response -> banda d'azione, tipicamente DEGRADING con EOL ~ settimana. */
    private static final double DEMO_DRIFT_FRACTION = 0.12;
    /** Ore operative seed per la demo: rende la proiezione EOL sensata e supera il warm-up. */
    private static final double DEMO_OPERATING_HOURS = 240.0;

    private final boolean demoEnabled;
    private final MqttIngestionService ingestionService;
    private final Repositories.LabRepository labRepository;
    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.SensorHealthRepository healthRepository;
    private final Repositories.MeasurementRepository measurementRepository;

    public DemoController(@Value("${incusense.demo.enabled:true}") boolean demoEnabled,
                          MqttIngestionService ingestionService,
                          Repositories.LabRepository labRepository,
                          Repositories.SensingHubRepository hubRepository,
                          Repositories.SensorHealthRepository healthRepository,
                          Repositories.MeasurementRepository measurementRepository) {
        this.demoEnabled = demoEnabled;
        this.ingestionService = ingestionService;
        this.labRepository = labRepository;
        this.hubRepository = hubRepository;
        this.healthRepository = healthRepository;
        this.measurementRepository = measurementRepository;
    }

    /** Scenari disponibili per la demo. */
    public record DemoScenarioInfo(String id, String label, String level, String description) {}

    public record DemoRequest(String scenario, String hubKey) {}

    public record DemoResult(String scenario, String hubKey, String level,
                             String injectedPayload, String note) {}

    @GetMapping("/scenarios")
    public List<DemoScenarioInfo> scenarios() {
        requireEnabled();
        return List.of(
            new DemoScenarioInfo("NORMAL",        "Condizioni nominali",      "OK",       "Valori nel range: nessun alert (ripristino visivo)."),
            new DemoScenarioInfo("CO2_LOW",       "Crollo CO₂",               "WARN",     "CO₂ sotto soglia incubatore (<30.000 ppm)."),
            new DemoScenarioInfo("CO2_HIGH",      "Eccesso CO₂",              "WARN",     "CO₂ sopra soglia (>70.000 ppm)."),
            new DemoScenarioInfo("OVERHEAT",      "Sovratemperatura",         "WARN",     "Temperatura ambiente fuori range (>38,5 °C)."),
            new DemoScenarioInfo("HUMIDITY_LOW",  "Umidità bassa",            "INFO",     "Umidità sotto il range preferito (<80%)."),
            new DemoScenarioInfo("RAIL_FAILURE",  "Guasto alimentazione 12V", "CRITICAL", "Rail 12V fuori tolleranza: alert CRITICO + email."),
            new DemoScenarioInfo("SENSOR_DRIFT",  "Drift sensore (reale)",    "WARN",     "Inietta una lettura con risposta derivata (~12%): muove il drift meter, aggiorna lo stato salute e attiva la manutenzione predittiva.")
        );
    }

    @PostMapping("/scenario")
    public DemoResult trigger(@AuthenticationPrincipal AuthenticatedLabUser principal,
                              @RequestBody DemoRequest request) {
        requireEnabled();
        String labId = principal.getLabId();
        Lab lab = labRepository.findByLabId(labId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lab not found"));

        String hubKey = resolveHubKey(labId, request.hubKey(), lab);
        String scenario = request.scenario() == null ? "" : request.scenario().trim().toUpperCase();

        if ("SENSOR_DRIFT".equals(scenario)) {
            SensingHub hub = hubRepository.findByHubKeyAndLab_LabId(hubKey, labId)
                    .orElseGet(() -> hubRepository.save(new SensingHub(hubKey, hubKey, lab)));

            // 1) Seed della salute sensore: baseline a regime + ore operative, cosi' il drift viene
            //    calcolato davvero (supera il warm-up) e la proiezione EOL e' sensata.
            SensorHealth health = healthRepository.findById(hub.getId()).orElseGet(() -> new SensorHealth(hub));
            if (health.getInstallResponse() == null || health.getInstallResponse() <= 0) {
                health.setInstallResponse(DEMO_BASELINE_RESPONSE);
            }
            if (health.getOperatingHours() == null || health.getOperatingHours() < DEMO_OPERATING_HOURS) {
                health.setOperatingHours(DEMO_OPERATING_HOURS);
            }
            healthRepository.save(health);

            // 2) Inietta una lettura A REGIME con risposta derivata (+12%) nel PERCORSO REALE:
            //    DriftMonitoringService calcola observedDriftPct, aggiorna lo stato (DEGRADING/CRITICAL),
            //    emette l'alert di manutenzione predittiva e muove il drift meter in dashboard.
            double baseline = health.getInstallResponse();
            double driftedResp = baseline * (1.0 + DEMO_DRIFT_FRACTION);
            int driftedAdc = (int) Math.round(1380.0 * (1.0 + driftedResp)); // |1380-adc|/1380 = resp (adc>baseline)
            long ts = nextStreamTs(labId, hubKey);
            String payload = String.format(java.util.Locale.US,
                    "{\"ts\":%d,\"co2_ppm\":50000.0,\"heater_temp\":250.0,\"env_temp\":37.0," +
                    "\"env_hum\":95.0,\"rail_12v\":12.0,\"raw_adc\":%d,\"sensor_response\":%.4f}",
                    ts, driftedAdc, driftedResp);

            String topic = "incusense/labs/" + labId + "/hubs/" + hubKey + "/telemetry";
            ingestionService.ingest(topic, payload);
            log.info("[DEMO] SENSOR_DRIFT (reale) injected for hub {} -> drift ~{}% ({})",
                    hubKey, Math.round(DEMO_DRIFT_FRACTION * 100), payload);

            return new DemoResult(scenario, hubKey, "WARN", payload,
                    "Drift reale iniettato (~12%): drift meter e stato salute aggiornati + alert manutenzione predittiva.");
        }

        String[] s = scenarioValues(scenario);
        String level = s[0];
        long ts = nextStreamTs(labId, hubKey);
        String payload = String.format(java.util.Locale.US,
                "{\"ts\":%d,\"co2_ppm\":%s,\"heater_temp\":%s,\"env_temp\":%s," +
                "\"env_hum\":%s,\"rail_12v\":%s,\"raw_adc\":%s,\"sensor_response\":%s}",
                ts, s[1], s[2], s[3], s[4], s[5], s[6], s[7]);

        String topic = "incusense/labs/" + labId + "/hubs/" + hubKey + "/telemetry";
        ingestionService.ingest(topic, payload);
        log.info("[DEMO] scenario {} injected for hub {} -> {}", scenario, hubKey, payload);

        return new DemoResult(scenario, hubKey, level, payload,
                "OK".equals(level) ? "Telemetria nominale iniettata (nessun alert)."
                                   : "Telemetria iniettata: alert " + level + " atteso + email.");
    }

    /**
     * Timestamp ANCORATO allo stream del nodo: ultimo timestamp di misura per quell'hub + 1 s
     * (oppure ora, se non ci sono ancora misure). Evita che un test demo finisca "nel futuro"
     * rispetto allo stream reale (es. se i nodi usano un clock diverso/non-NTP): il punto demo
     * resta SEMPRE l'ultimo della serie, e le misure successive si accodano in ordine.
     */
    private long nextStreamTs(String labId, String hubKey) {
        List<Measurement> latest = measurementRepository
                .findByHubAndLabOrderByRecordedAtDesc(hubKey, labId, PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return Instant.now().getEpochSecond();
        }
        return latest.get(0).getRecordedAt().getEpochSecond() + 1;
    }

    /**
     * Ritorna [level, co2, heater, envTemp, envHum, rail, rawAdc, sensorResponse].
     * raw_adc e sensor_response sono COERENTI con la formula del firmware
     * (sensor_response = |1380 - raw_adc| / 1380, baseline aria ADC 1380, setpoint 50k ADC 2115).
     */
    private String[] scenarioValues(String scenario) {
        return switch (scenario) {
            case "NORMAL"       -> new String[]{"OK",       "50000", "250", "37.0", "95.0", "12.0", "2115", "0.5326"};
            case "CO2_LOW"      -> new String[]{"WARN",     "20000", "250", "37.0", "95.0", "12.0", "1670", "0.2103"};
            case "CO2_HIGH"     -> new String[]{"WARN",     "82000", "250", "37.0", "95.0", "12.0", "2589", "0.8761"};
            case "OVERHEAT"     -> new String[]{"WARN",     "50000", "250", "42.0", "95.0", "12.0", "2115", "0.5326"};
            case "HUMIDITY_LOW" -> new String[]{"INFO",     "50000", "250", "37.0", "70.0", "12.0", "2115", "0.5326"};
            case "RAIL_FAILURE" -> new String[]{"CRITICAL", "50000", "250", "37.0", "95.0", "10.4", "2115", "0.5326"};
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown demo scenario: " + scenario);
        };
    }

    private String resolveHubKey(String labId, String requested, Lab lab) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        List<SensingHub> hubs = hubRepository.findByLab_LabId(labId);
        if (!hubs.isEmpty()) {
            return hubs.get(0).getHubKey();
        }
        // Nessun hub ancora: usa un nodo demo dedicato (verrà creato dall'ingestione).
        return labId + "_demo_node";
    }

    private void requireEnabled() {
        if (!demoEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode disabled");
        }
    }
}
