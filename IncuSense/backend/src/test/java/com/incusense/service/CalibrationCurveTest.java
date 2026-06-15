package com.incusense.service;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test della curva di calibrazione power-law (response = a*ppm^b) con i coefficienti
 * della regressione owner: alpha=0.345 (=> a=10^alpha), beta=0.254 (=b),
 * valid_ppm operativo incubatore=[30000,70000].
 * Puro (niente Spring/DB).
 */
class CalibrationCurveTest {

    private static final String FITTED_JSON = """
            {"model":"power_law","fit_space":"log10","alpha":0.345,"beta":0.254,
             "a":2.2131,"b":0.254,"r2":0.98,"valid_ppm":[30000,70000],
             "experimental_ppm":[250,5000],"ref_temp_c":250}
            """;

    private static final String UNFITTED_JSON = """
            {"model":"power_law","a":null,"b":null,"valid_ppm":[30000,70000]}
            """;

    @Test
    void parsesFittedCoefficients() {
        CalibrationCurve c = CalibrationCurve.fromJson(FITTED_JSON);
        assertTrue(c.isFitted());
        assertEquals(0.254, c.exponentB(), 1e-9);
        assertTrue(c.r2().isPresent());
        assertEquals(0.98, c.r2().get(), 1e-9);
        assertEquals(30000.0, c.validPpmMin(), 1e-9);
        assertEquals(70000.0, c.validPpmMax(), 1e-9);
    }

    @Test
    void roundTripsPpmThroughResponseWithinRange() {
        CalibrationCurve c = CalibrationCurve.fromJson(FITTED_JSON);
        for (double ppm : new double[]{30000, 45000, 50000, 60000, 70000}) {
            OptionalDouble resp = c.ppmToResponse(ppm);
            assertTrue(resp.isPresent(), "response atteso entro range per ppm=" + ppm);
            OptionalDouble back = c.responseToPpm(resp.getAsDouble());
            assertTrue(back.isPresent(), "inversione attesa per ppm=" + ppm);
            assertEquals(ppm, back.getAsDouble(), 1e-6, "round-trip ppm=" + ppm);
        }
    }

    @Test
    void refusesExtrapolationOutsideValidRange() {
        CalibrationCurve c = CalibrationCurve.fromJson(FITTED_JSON);
        assertFalse(c.ppmToResponse(29999).isPresent(), "sotto il range incubatore");
        assertFalse(c.ppmToResponse(70001).isPresent(), "sopra il range incubatore");
        assertTrue(c.ppmToResponse(50000).isPresent(), "setpoint incubatore dentro il range operativo");
        // un response enorme implicherebbe ppm >> 70000 -> rifiutato
        assertFalse(c.responseToPpm(1_000_000).isPresent());
        assertFalse(c.responseToPpm(0).isPresent());
        assertFalse(c.responseToPpm(-1).isPresent());
    }

    @Test
    void driftToPpmFractionUsesExponentOnly() {
        CalibrationCurve c = CalibrationCurve.fromJson(FITTED_JSON);
        // dPpm/ppm = (dResponse/response) / b ; b=0.254
        assertEquals(0.05 / 0.254, c.responseDriftToPpmFraction(0.05).getAsDouble(), 1e-9);
        assertEquals(0.20 / 0.254, c.responseDriftToPpmFraction(0.20).getAsDouble(), 1e-9);
    }

    @Test
    void unfittedOrMalformedJsonYieldsUnfittedCurve() {
        assertFalse(CalibrationCurve.fromJson(UNFITTED_JSON).isFitted());
        assertFalse(CalibrationCurve.fromJson(null).isFitted());
        assertFalse(CalibrationCurve.fromJson("not-json").isFitted());
        assertFalse(CalibrationCurve.fromJson("{}").isFitted());
        // su curva non fittata, nessuna conversione disponibile
        assertFalse(CalibrationCurve.fromJson(UNFITTED_JSON).ppmToResponse(50000).isPresent());
        assertFalse(CalibrationCurve.fromJson(UNFITTED_JSON).responseDriftToPpmFraction(0.1).isPresent());
    }
}
