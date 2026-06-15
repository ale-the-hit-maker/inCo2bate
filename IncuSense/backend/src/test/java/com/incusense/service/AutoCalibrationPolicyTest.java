package com.incusense.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Truth-table dei guard-rail e delle bande dell'auto-calibrazione (logica pura).
 * Config: T_action=5%, EOL=20%, minSamples=20, maxOffset=15000 ppm.
 */
class AutoCalibrationPolicyTest {

    private static final AutoCalibrationPolicy.Config CFG =
            new AutoCalibrationPolicy.Config(5.0, 20.0, 20, 15000.0);

    /** Caso "tutto ok, banda azionabile" (curva fittata, rate nel regime drift). */
    private AutoCalibrationPolicy.Inputs base() {
        return new AutoCalibrationPolicy.Inputs(
                true, true, "OK", 8.0, 30, true, false, 8000.0, true, true);
    }

    private AutoCalibrationPolicy.Action act(AutoCalibrationPolicy.Inputs in) {
        return AutoCalibrationPolicy.decide(CFG, in).action();
    }

    private AutoCalibrationPolicy.Inputs with(AutoCalibrationPolicy.Inputs b,
                                              Boolean sane, Boolean regime, String health, Double drift,
                                              Long n, Boolean cool, Boolean pending, Double impl) {
        return new AutoCalibrationPolicy.Inputs(
                sane != null ? sane : b.inputsSane(),
                regime != null ? regime : b.atRegime(),
                health != null ? health : b.healthStatus(),
                drift != null ? drift : b.driftEwmaPct(),
                n != null ? n : b.sampleCount(),
                cool != null ? cool : b.cooldownElapsed(),
                pending != null ? pending : b.hasPendingCommand(),
                impl != null ? impl : b.impliedDeltaPpm(),
                b.curveFitted(),
                b.rateWithinDriftRegime());
    }

    @Test
    void actionableWithinGuardrailsIssuesCommand() {
        assertEquals(AutoCalibrationPolicy.Action.COMMAND, act(base()));
    }

    @Test
    void negativeDriftAlsoCommands() {
        assertEquals(AutoCalibrationPolicy.Action.COMMAND,
                act(with(base(), null, null, null, -8.0, null, null, null, null)));
    }

    @Test
    void belowThresholdDoesNothing() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), null, null, null, 3.0, null, null, null, null)));
    }

    @Test
    void criticalBandRaisesNoAutoCorrection() {
        assertEquals(AutoCalibrationPolicy.Action.CRITICAL_NOACTION,
                act(with(base(), null, null, null, 25.0, null, null, null, null)));
    }

    @Test
    void notAtRegimeGatesOut() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), null, false, null, null, null, null, null, null)));
    }

    @Test
    void insufficientSamplesWaits() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), null, null, null, null, 5L, null, null, null)));
    }

    @Test
    void pendingCommandSuppressesNew() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), null, null, null, null, null, null, true, null)));
    }

    @Test
    void cooldownSuppresses() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), null, null, null, null, null, false, null, null)));
    }

    @Test
    void criticalHealthSkips() {
        assertEquals(AutoCalibrationPolicy.Action.SKIP,
                act(with(base(), null, null, "CRITICAL", null, null, null, null, null)));
    }

    @Test
    void oversizedCorrectionSkips() {
        assertEquals(AutoCalibrationPolicy.Action.SKIP,
                act(with(base(), null, null, null, null, null, null, null, 20000.0)));
    }

    @Test
    void invalidInputDoesNothing() {
        assertEquals(AutoCalibrationPolicy.Action.NONE,
                act(with(base(), false, null, null, null, null, null, null, null)));
    }

    @Test
    void thresholdBoundaryIsActionable() {
        assertEquals(AutoCalibrationPolicy.Action.COMMAND,
                act(with(base(), null, null, null, 5.0, null, null, null, null)));
    }

    @Test
    void eolBoundaryIsCritical() {
        assertEquals(AutoCalibrationPolicy.Action.CRITICAL_NOACTION,
                act(with(base(), null, null, null, 20.0, null, null, null, null)));
    }

    @Test
    void unfittedCurveStaysAdvisoryNoAutoCorrection() {
        // banda azionabile ma nessuna curva assegnata -> solo advisory, niente comando
        AutoCalibrationPolicy.Inputs in = new AutoCalibrationPolicy.Inputs(
                true, true, "OK", 8.0, 30, true, false, 8000.0, false, true);
        assertEquals(AutoCalibrationPolicy.Action.NONE, act(in));
    }

    @Test
    void tooFastRateSkipsAsProcessAnomaly() {
        // variazione troppo rapida -> probabile guasto/anomalia, non drift -> SKIP
        AutoCalibrationPolicy.Inputs in = new AutoCalibrationPolicy.Inputs(
                true, true, "OK", 8.0, 30, true, false, 8000.0, true, false);
        assertEquals(AutoCalibrationPolicy.Action.SKIP, act(in));
    }
}
