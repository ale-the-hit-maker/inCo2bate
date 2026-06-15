package com.incusense.service;

import com.incusense.dto.Dtos;
import com.incusense.model.CalibrationEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-calibrazione semi-real-time a loop chiuso (vedi docs/AUTOCAL_v1_design.md).
 *
 * <p>Sorgente del drift: {@code sensor_response} auto-riferito (rispetto a installResponse),
 * mediato con EWMA sui soli campioni <b>a regime</b> (gating §5.0, anti-confondimento tra
 * drift del sensore e calo reale di CO2). In banda azionabile e dentro i guard-rail, pubblica
 * un comando {@code OFFSET_CAL} con {@code offset_ppm} = miglior stima della concentrazione
 * vera corrente (= setpoint dell'incubatore, controllato indipendentemente), il nodo ricava il
 * delta. L'esito e' tracciato come {@link CalibrationEvent} (PENDING->ACKED->VERIFIED|FAILED|SKIPPED).
 *
 * <p>Integrazione della curva di calibrazione: l'esponente {@code b=beta} (invariante di scala)
 * traduce il drift frazionale del response nell'errore frazionale di ppm
 * ({@code dPpm/ppm = (dResponse/response)/b}), usato per i guard-rail di ampiezza correzione.
 *
 * <p><b>Disabilitato di default</b> ({@code incusense.autocal.enabled=false}): non pubblica nulla
 * verso i nodi finche' non viene attivato esplicitamente, cosi' da non introdurre regressioni
 * sul percorso esistente prima della verifica end-to-end.
 */
@Service
public class AutoCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(AutoCalibrationService.class);
    private static final String COMMAND_TOPIC = "incusense/labs/%s/hubs/%s/commands";

    private final Repositories.CalibrationEventRepository eventRepository;
    private final CommandTransport commandTransport;
    private final SimpMessagingTemplate messagingTemplate;

    private final boolean enabled;
    private final double setpointPpm;
    private final double setpointTolPpm;
    private final double heaterTargetC;
    private final double heaterTolC;
    private final double ewmaAlpha;
    private final double minHoursBetweenCmd;
    private final int verifySamples;
    private final AutoCalibrationPolicy.Config policyConfig;

    private final Map<Long, HubState> state = new ConcurrentHashMap<>();

    public AutoCalibrationService(
            Repositories.CalibrationEventRepository eventRepository,
            CommandTransport commandTransport,
            SimpMessagingTemplate messagingTemplate,
            @Value("${incusense.autocal.enabled:false}") boolean enabled,
            @Value("${incusense.autocal.setpoint-ppm:50000}") double setpointPpm,
            @Value("${incusense.autocal.setpoint-tol-ppm:3000}") double setpointTolPpm,
            @Value("${incusense.autocal.heater-target-c:250}") double heaterTargetC,
            @Value("${incusense.autocal.heater-tol-c:15}") double heaterTolC,
            @Value("${incusense.autocal.action-threshold-pct:5}") double actionThresholdPct,
            @Value("${incusense.autocal.eol-threshold-pct:20}") double eolThresholdPct,
            @Value("${incusense.autocal.ewma-alpha:0.2}") double ewmaAlpha,
            @Value("${incusense.autocal.min-samples:20}") long minSamples,
            @Value("${incusense.autocal.min-hours-between-cmd:12}") double minHoursBetweenCmd,
            @Value("${incusense.autocal.max-offset-ppm:15000}") double maxOffsetPpm,
            @Value("${incusense.autocal.verify-samples:10}") int verifySamples) {
        this.eventRepository = eventRepository;
        this.commandTransport = commandTransport;
        this.messagingTemplate = messagingTemplate;
        this.enabled = enabled;
        this.setpointPpm = setpointPpm;
        this.setpointTolPpm = setpointTolPpm;
        this.heaterTargetC = heaterTargetC;
        this.heaterTolC = heaterTolC;
        this.ewmaAlpha = ewmaAlpha;
        this.minHoursBetweenCmd = minHoursBetweenCmd;
        this.verifySamples = verifySamples;
        this.policyConfig = new AutoCalibrationPolicy.Config(
                actionThresholdPct, eolThresholdPct, minSamples, maxOffsetPpm);
        if (enabled) {
            log.info("[AUTOCAL] enabled (setpoint={} ppm, tol={} ppm, T_action={}%, maxOffset={} ppm)",
                    setpointPpm, setpointTolPpm, actionThresholdPct, maxOffsetPpm);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Invocato da {@link DriftMonitoringService} dopo l'aggiornamento del drift per ogni campione. */
    public void evaluate(SensingHub hub, SensorHealth health, Measurement m) {
        if (!enabled || hub == null || hub.getId() == null) {
            return;
        }
        Double resp = m.getSensorResponse();
        if (resp == null) {
            return; // payload vecchio firmware: nessun campo drift
        }
        Long hubId = hub.getId();
        HubState st = state.computeIfAbsent(hubId, k -> new HubState());
        Instant now = Instant.now();

        boolean inputsSane = resp > 0 && m.getCo2Ppm() > 0;
        boolean atRegime = isAtRegime(m);

        // EWMA del response solo sui campioni a regime
        if (atRegime && inputsSane) {
            st.ewmaResponse = (st.ewmaCount == 0) ? resp : ewmaAlpha * resp + (1 - ewmaAlpha) * st.ewmaResponse;
            st.ewmaCount++;
        }

        // Verifica post-cal (su co2_ppm vs setpoint) di un comando gia' ACKED
        if (st.awaitingVerificationEventId != null && atRegime && inputsSane) {
            verifyPostCal(hub, st, m, now);
        }

        Double install = health.getInstallResponse();
        if (install == null || install == 0.0 || st.ewmaCount == 0) {
            return; // baseline non ancora catturata
        }
        double driftEwmaPct = (st.ewmaResponse - install) / install * 100.0;

        CalibrationCurve curve = CalibrationCurve.fromJson(
                health.getCurve() != null ? health.getCurve().getPointsJson() : null);
        double b = curve.isFitted() ? curve.exponentB() : 1.0; // fallback prudente: curva non assegnata
        double impliedDeltaPpm = (driftEwmaPct / 100.0 / b) * setpointPpm;

        boolean cooldownElapsed = st.lastCommandAt == null
                || Duration.between(st.lastCommandAt, now).toSeconds() >= minHoursBetweenCmd * 3600.0;
        boolean hasPending = st.awaitingVerificationEventId != null
                || !eventRepository.findByHub_IdAndStatusOrderByCreatedAtDesc(hubId, "PENDING").isEmpty();

        AutoCalibrationPolicy.Inputs in = new AutoCalibrationPolicy.Inputs(
                inputsSane, atRegime, health.getHealthStatus(), driftEwmaPct, st.ewmaCount,
                cooldownElapsed, hasPending, impliedDeltaPpm);
        AutoCalibrationPolicy.Decision d = AutoCalibrationPolicy.decide(policyConfig, in);

        switch (d.action()) {
            case COMMAND -> sendOffsetCal(hub, st, driftEwmaPct, now);
            case SKIP -> recordSkip(hub, st, driftEwmaPct, d.reason(), now);
            default -> { /* NONE / CRITICAL_NOACTION: nessun evento (l'alert e' gestito dal drift monitor) */ }
        }
    }

    /** Trigger manuale dell'operatore: invia subito OFFSET_CAL al setpoint (bypassa soglia/cooldown). */
    public CalibrationEvent triggerManual(SensingHub hub, SensorHealth health) {
        Long hubId = hub.getId();
        HubState st = state.computeIfAbsent(hubId, k -> new HubState());
        if (st.awaitingVerificationEventId != null
                || !eventRepository.findByHub_IdAndStatusOrderByCreatedAtDesc(hubId, "PENDING").isEmpty()) {
            throw new IllegalStateException("Un comando di calibrazione e' gia' in corso per questo hub");
        }
        Double drift = (health != null) ? health.getObservedDriftPct() : null;
        return sendOffsetCal(hub, st, drift != null ? drift : 0.0, Instant.now());
    }

    /** Gestione dell'ACK ricevuto dal nodo sul topic {@code .../ack}. */
    public void handleAck(long eventId, String status) {
        CalibrationEvent ev = eventRepository.findById(eventId).orElse(null);
        if (ev == null) {
            log.warn("[AUTOCAL] ACK per event_id sconosciuto: {}", eventId);
            return;
        }
        Instant now = Instant.now();
        if ("OK".equalsIgnoreCase(status)) {
            ev.setStatus("ACKED");
            ev.setAckedAt(now);
            eventRepository.save(ev);
            HubState st = state.computeIfAbsent(ev.getHub().getId(), k -> new HubState());
            st.awaitingVerificationEventId = ev.getId();
            st.verifySamplesLeft = verifySamples;
            log.info("[AUTOCAL] event {} ACKED da hub {}", eventId, ev.getHub().getHubKey());
        } else {
            ev.setStatus("FAILED");
            ev.setReason("nodo: status=" + status);
            eventRepository.save(ev);
            log.warn("[AUTOCAL] event {} FAILED (status nodo={})", eventId, status);
        }
        broadcast(ev);
    }

    // ----------------------------------------------------------------------------------------

    private boolean isAtRegime(Measurement m) {
        return Math.abs(m.getCo2Ppm() - setpointPpm) <= setpointTolPpm
                && Math.abs(m.getHeaterTemp() - heaterTargetC) <= heaterTolC;
    }

    private CalibrationEvent sendOffsetCal(SensingHub hub, HubState st, double driftPct, Instant now) {
        // Riferimento auto-riferito: la miglior stima della concentrazione vera corrente e' il
        // setpoint (l'incubatore lo mantiene via controllo indipendente). Il nodo ricava il delta.
        double target = setpointPpm;
        CalibrationEvent ev = eventRepository.save(new CalibrationEvent(
                hub, "OFFSET_CAL", "PENDING", target, driftPct, "auto: drift azionabile"));

        String topic = String.format(COMMAND_TOPIC, labId(hub), hub.getHubKey());
        String json = String.format(Locale.US,
                "{\"command\":\"OFFSET_CAL\",\"offset_ppm\":%.1f,\"event_id\":%d}", target, ev.getId());

        boolean ok = commandTransport.publish(topic, json);
        if (!ok) {
            ev.setStatus("FAILED");
            ev.setReason("pubblicazione comando MQTT fallita");
            eventRepository.save(ev);
            log.warn("[AUTOCAL] publish OFFSET_CAL fallita hub={} event={}", hub.getHubKey(), ev.getId());
        } else {
            log.info("[AUTOCAL] OFFSET_CAL inviato hub={} event={} target={} ppm (drift={}%)",
                    hub.getHubKey(), ev.getId(), target, String.format(Locale.US, "%.1f", driftPct));
        }
        st.lastCommandAt = now;
        broadcast(ev);
        return ev;
    }

    private void recordSkip(SensingHub hub, HubState st, double driftPct, String reason, Instant now) {
        CalibrationEvent ev = eventRepository.save(new CalibrationEvent(
                hub, "OFFSET_CAL", "SKIPPED", null, driftPct, reason));
        st.lastCommandAt = now; // tratta lo skip come azione ai fini del cooldown -> niente spam
        log.info("[AUTOCAL] SKIPPED hub={} drift={}%: {}",
                hub.getHubKey(), String.format(Locale.US, "%.1f", driftPct), reason);
        broadcast(ev);
    }

    private void verifyPostCal(SensingHub hub, HubState st, Measurement m, Instant now) {
        CalibrationEvent ev = eventRepository.findById(st.awaitingVerificationEventId).orElse(null);
        if (ev == null) {
            st.awaitingVerificationEventId = null;
            return;
        }
        boolean withinTol = Math.abs(m.getCo2Ppm() - setpointPpm) <= setpointTolPpm;
        if (withinTol) {
            ev.setStatus("VERIFIED");
            ev.setVerifiedAt(now);
            eventRepository.save(ev);
            st.awaitingVerificationEventId = null;
            log.info("[AUTOCAL] event {} VERIFIED (co2 rientrata entro tolleranza dal setpoint)", ev.getId());
            broadcast(ev);
        } else if (--st.verifySamplesLeft <= 0) {
            ev.setStatus("FAILED");
            ev.setReason("post-cal: co2 ancora fuori tolleranza dopo la correzione");
            ev.setVerifiedAt(now);
            eventRepository.save(ev);
            st.awaitingVerificationEventId = null;
            log.warn("[AUTOCAL] event {} FAILED in verifica post-cal", ev.getId());
            broadcast(ev);
        }
    }

    private void broadcast(CalibrationEvent ev) {
        String labId = labId(ev.getHub());
        if (labId != null) {
            messagingTemplate.convertAndSend("/topic/calibration/" + labId, Dtos.CalibrationEventResponse.from(ev));
        }
    }

    private static String labId(SensingHub hub) {
        return hub.getLab() != null ? hub.getLab().getLabId() : null;
    }

    /** Stato per-hub dell'auto-calibrazione (EWMA, cooldown, verifica in corso). */
    private static final class HubState {
        double ewmaResponse;
        long ewmaCount;
        Instant lastCommandAt;
        Long awaitingVerificationEventId;
        int verifySamplesLeft;
    }
}
