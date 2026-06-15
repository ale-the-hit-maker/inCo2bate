package com.incusense.controller;

import com.incusense.model.Lab;
import com.incusense.model.SensingHub;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import com.incusense.service.AlertService;
import com.incusense.service.MqttIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

    private final boolean demoEnabled;
    private final MqttIngestionService ingestionService;
    private final AlertService alertService;
    private final Repositories.LabRepository labRepository;
    private final Repositories.SensingHubRepository hubRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public DemoController(@Value("${incusense.demo.enabled:true}") boolean demoEnabled,
                          MqttIngestionService ingestionService,
                          AlertService alertService,
                          Repositories.LabRepository labRepository,
                          Repositories.SensingHubRepository hubRepository,
                          SimpMessagingTemplate messagingTemplate) {
        this.demoEnabled = demoEnabled;
        this.ingestionService = ingestionService;
        this.alertService = alertService;
        this.labRepository = labRepository;
        this.hubRepository = hubRepository;
        this.messagingTemplate = messagingTemplate;
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
            new DemoScenarioInfo("SENSOR_DRIFT",  "Drift sensore",            "WARN",     "Allarme di manutenzione predittiva (drift del sensore).")
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
            alertService.emitDrift(hub, "WARN",
                    "[DEMO] Drift sensore rilevato: avvio manutenzione predittiva")
                    .ifPresent(a -> messagingTemplate.convertAndSend("/topic/alerts/" + labId, a));
            log.info("[DEMO] SENSOR_DRIFT scenario fired for hub {}", hubKey);
            return new DemoResult(scenario, hubKey, "WARN", "(drift alert)",
                    "Alert di drift emesso: verifica dashboard + email.");
        }

        String[] s = scenarioValues(scenario);
        String level = s[0];
        String payload = String.format(java.util.Locale.US,
                "{\"ts\":%d,\"co2_ppm\":%s,\"heater_temp\":%s,\"env_temp\":%s," +
                "\"env_hum\":%s,\"rail_12v\":%s,\"raw_adc\":%s,\"sensor_response\":%s}",
                Instant.now().getEpochSecond(), s[1], s[2], s[3], s[4], s[5], s[6], s[7]);

        String topic = "incusense/labs/" + labId + "/hubs/" + hubKey + "/telemetry";
        ingestionService.ingest(topic, payload);
        log.info("[DEMO] scenario {} injected for hub {} -> {}", scenario, hubKey, payload);

        return new DemoResult(scenario, hubKey, level, payload,
                "OK".equals(level) ? "Telemetria nominale iniettata (nessun alert)."
                                   : "Telemetria iniettata: alert " + level + " atteso + email.");
    }

    /** Ritorna [level, co2, heater, envTemp, envHum, rail, rawAdc, sensorResponse]. */
    private String[] scenarioValues(String scenario) {
        return switch (scenario) {
            case "NORMAL"       -> new String[]{"OK",       "50000", "250", "37.0", "95.0", "12.0", "1747", "0.2000"};
            case "CO2_LOW"      -> new String[]{"WARN",     "20000", "250", "37.0", "95.0", "12.0", "1100", "0.1200"};
            case "CO2_HIGH"     -> new String[]{"WARN",     "82000", "250", "37.0", "95.0", "12.0", "2300", "0.3500"};
            case "OVERHEAT"     -> new String[]{"WARN",     "50000", "250", "42.0", "95.0", "12.0", "1747", "0.2000"};
            case "HUMIDITY_LOW" -> new String[]{"INFO",     "50000", "250", "37.0", "70.0", "12.0", "1747", "0.2000"};
            case "RAIL_FAILURE" -> new String[]{"CRITICAL", "50000", "250", "37.0", "95.0", "10.4", "1747", "0.2000"};
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
