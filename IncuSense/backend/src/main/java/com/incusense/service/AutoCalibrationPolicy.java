package com.incusense.service;

/**
 * Logica di decisione PURA dell'auto-calibrazione (nessun side-effect, nessuna dipendenza Spring):
 * dato lo stato corrente decide se non fare nulla, inviare una correzione, saltare per guard-rail,
 * o limitarsi all'alert in banda critica. Isolata qui per essere testabile senza broker/DB
 * (vedi {@code AutoCalibrationPolicyTest} e docs/AUTOCAL_v1_design.md §5).
 */
public final class AutoCalibrationPolicy {

    private AutoCalibrationPolicy() {
    }

    public enum Action {
        /** Entro tolleranza / pre-condizioni non soddisfatte: nessuna azione, nessun evento. */
        NONE,
        /** Banda azionabile e tutti i guard-rail superati: invia OFFSET_CAL. */
        COMMAND,
        /** Banda azionabile ma un guard-rail blocca: registra SKIPPED. */
        SKIP,
        /** Drift oltre soglia EOL: nessuna auto-correzione, solo alert (gia' emesso dal drift monitor). */
        CRITICAL_NOACTION
    }

    /** Soglie e parametri (da {@code incusense.autocal.*}). */
    public record Config(
            double actionThresholdPct,
            double eolThresholdPct,
            long minSamples,
            double maxOffsetPpm) {
    }

    /** Fotografia dello stato al momento della valutazione di un campione. */
    public record Inputs(
            boolean inputsSane,
            boolean atRegime,
            String healthStatus,
            double driftEwmaPct,
            long sampleCount,
            boolean cooldownElapsed,
            boolean hasPendingCommand,
            double impliedDeltaPpm) {
    }

    public record Decision(Action action, String reason) {
    }

    public static Decision decide(Config cfg, Inputs in) {
        if (!in.inputsSane()) {
            return new Decision(Action.NONE, "input non valido o incompleto");
        }
        if (!in.atRegime()) {
            return new Decision(Action.NONE, "sistema non a regime (gating §5.0)");
        }

        double absDrift = Math.abs(in.driftEwmaPct());

        if (absDrift < cfg.actionThresholdPct()) {
            return new Decision(Action.NONE, "drift entro tolleranza");
        }
        if (absDrift >= cfg.eolThresholdPct()) {
            return new Decision(Action.CRITICAL_NOACTION, "drift oltre soglia EOL: solo alert predittivo");
        }

        // --- banda ACTIONABLE: applica i guard-rail ---
        if (in.sampleCount() < cfg.minSamples()) {
            return new Decision(Action.NONE, "campioni a regime insufficienti");
        }
        if (in.hasPendingCommand()) {
            return new Decision(Action.NONE, "comando precedente in attesa di ACK");
        }
        if (!in.cooldownElapsed()) {
            return new Decision(Action.NONE, "cooldown attivo");
        }
        if ("CRITICAL".equals(in.healthStatus())) {
            return new Decision(Action.SKIP, "salute sensore CRITICAL: correzione non sicura");
        }
        if (Math.abs(in.impliedDeltaPpm()) > cfg.maxOffsetPpm()) {
            return new Decision(Action.SKIP, String.format(
                    "correzione stimata %.0f ppm oltre MAX_OFFSET_PPM (%.0f)",
                    in.impliedDeltaPpm(), cfg.maxOffsetPpm()));
        }
        return new Decision(Action.COMMAND, "drift azionabile entro guard-rail");
    }
}
