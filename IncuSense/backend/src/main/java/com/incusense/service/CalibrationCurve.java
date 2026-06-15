package com.incusense.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Valore immutabile che incapsula la curva di calibrazione power-law del sensore CO2
 * (Na:In2O3), letta dal {@code points_json} di una {@code DriftReferenceCurve}.
 *
 * <p>Modello: {@code response = a * ppm^b}  &lt;=&gt;  {@code log10(response) = alpha + beta*log10(ppm)}.
 * Coefficienti dalla regressione lineare fornita dall'owner (vedi {@code V5__autocal.sql}):
 * {@code alpha=0.345} (intercetta, base 10), {@code beta=0.254} (esponente), {@code R^2=0.98}.
 *
 * <h3>Nota critica sulle unita' (§6 del contratto)</h3>
 * <ul>
 *   <li><b>{@link #exponentB() b}</b> e la dispersione log-log sono <i>invarianti di scala</i>:
 *       una rinormalizzazione moltiplicativa del response sposta solo l'intercetta. Quindi
 *       {@link #responseDriftToPpmFraction(double)} (che usa solo {@code b}) e' valido anche
 *       per il {@code sensor_response} del firmware ({@code |I_air-I|/I_air} in [0,1]).</li>
 *   <li><b>{@code a}</b> (intercetta) e' legata alla definizione di response del paper
 *       (rapporto di conduttanza ~4..13), <i>diversa</i> da quella del firmware. Percio'
 *       {@link #responseToPpm(double)} vale SOLO per input sulla scala della curva, e non va
 *       applicato al {@code sensor_response} grezzo del nodo.</li>
 *   <li>{@link #isInRangePpm(double)} riflette {@code valid_ppm=[250,5000]}: oltre il range la
 *       curva non e' estrapolabile (setpoint incubatore 30k-50k ppm).</li>
 * </ul>
 */
public final class CalibrationCurve {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Tolleranza relativa sui bordi del range (evita di escludere gli estremi per errore floating). */
    private static final double RANGE_EPS = 1e-9;

    private final boolean fitted;
    private final double a;        // response = a * ppm^b  (scala paper)
    private final double b;        // esponente = beta (invariante di scala)
    private final Double r2;
    private final double validPpmMin;
    private final double validPpmMax;

    private CalibrationCurve(boolean fitted, double a, double b, Double r2,
                            double validPpmMin, double validPpmMax) {
        this.fitted = fitted;
        this.a = a;
        this.b = b;
        this.r2 = r2;
        this.validPpmMin = validPpmMin;
        this.validPpmMax = validPpmMax;
    }

    /** Curva "non fittata": coefficienti assenti -> nessuna conversione disponibile. */
    public static CalibrationCurve unfitted() {
        return new CalibrationCurve(false, Double.NaN, Double.NaN, null, Double.NaN, Double.NaN);
    }

    /**
     * Costruisce una curva dal {@code points_json}. Se il JSON e' assente/malformato o i
     * coefficienti non sono presenti, ritorna una curva {@link #unfitted()} (mai null, mai eccezioni):
     * un dato malformato dal DB non deve far cadere il chiamante (§8 osservabilita').
     */
    public static CalibrationCurve fromJson(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) {
            return unfitted();
        }
        try {
            JsonNode n = MAPPER.readTree(pointsJson);
            JsonNode aNode = n.get("a");
            JsonNode bNode = n.get("b");
            if (aNode == null || aNode.isNull() || bNode == null || bNode.isNull()) {
                return unfitted();
            }
            double a = aNode.asDouble();
            double b = bNode.asDouble();
            if (!(a > 0) || b == 0.0 || Double.isNaN(a) || Double.isNaN(b)) {
                return unfitted(); // power-law non invertibile
            }
            Double r2 = (n.hasNonNull("r2")) ? n.get("r2").asDouble() : null;

            double lo = 0.0, hi = Double.POSITIVE_INFINITY;
            JsonNode range = n.get("valid_ppm");
            if (range != null && range.isArray() && range.size() == 2) {
                lo = range.get(0).asDouble();
                hi = range.get(1).asDouble();
            }
            return new CalibrationCurve(true, a, b, r2, lo, hi);
        } catch (Exception ex) {
            return unfitted();
        }
    }

    public boolean isFitted() {
        return fitted;
    }

    /** Esponente b della power-law (= beta della regressione). Invariante di scala. */
    public double exponentB() {
        return b;
    }

    public Optional<Double> r2() {
        return Optional.ofNullable(r2);
    }

    public boolean isInRangePpm(double ppm) {
        return fitted
                && ppm >= validPpmMin * (1 - RANGE_EPS)
                && ppm <= validPpmMax * (1 + RANGE_EPS);
    }

    public double validPpmMin() {
        return validPpmMin;
    }

    public double validPpmMax() {
        return validPpmMax;
    }

    /**
     * Concentrazione stimata da un response <b>sulla scala della curva</b> (paper), entro range.
     * Ritorna vuoto se la curva non e' fittata, l'input non e' positivo, o la ppm risultante
     * cade fuori da {@code valid_ppm} (niente estrapolazione).
     * NON usare con il sensor_response grezzo del firmware (scala diversa).
     */
    public OptionalDouble responseToPpm(double responseCurveScale) {
        if (!fitted || !(responseCurveScale > 0)) {
            return OptionalDouble.empty();
        }
        double ppm = Math.pow(responseCurveScale / a, 1.0 / b);
        if (!Double.isFinite(ppm) || !isInRangePpm(ppm)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(ppm);
    }

    /** Response atteso (scala curva) a una data concentrazione, entro range. */
    public OptionalDouble ppmToResponse(double ppm) {
        if (!isInRangePpm(ppm)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(a * Math.pow(ppm, b));
    }

    /**
     * Converte una variazione frazionale del response in variazione frazionale di ppm,
     * usando solo l'esponente {@code b} (invariante di scala): da {@code response = a*ppm^b}
     * segue, al primo ordine, {@code dResponse/response = b * dPpm/ppm}, quindi
     * {@code dPpm/ppm = (dResponse/response) / b}.
     *
     * <p>E' il ponte fisicamente fondato tra il drift osservato sul {@code sensor_response}
     * (auto-riferito, adimensionale) e l'errore di concentrazione corrispondente — usato
     * dall'auto-calibrazione per stimare l'entita' della correzione e i guard-rail.
     *
     * @param responseDriftFraction es. +0.10 per un drift del +10% sul response
     * @return frazione di errore stimata sulla ppm (vuoto se la curva non e' fittata)
     */
    public OptionalDouble responseDriftToPpmFraction(double responseDriftFraction) {
        if (!fitted) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(responseDriftFraction / b);
    }
}
