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
 * Coefficienti dalla regressione lineare fornita dall'owner (vedi {@code V6__incubator_autocal_finalization.sql}):
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
 *   <li>{@link #isInRangePpm(double)} riflette il range operativo incubatore
 *       {@code valid_ppm=[30000,70000]} attorno al setpoint 5% CO2 (50.000 ppm). Il range
 *       sperimentale dei paper resta tracciato nel JSON come {@code experimental_ppm=[250,5000]}.</li>
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
    private final Double sigmaLog;     // dispersione log10 dei residui (ampiezza banda); null se assente
    private final double anchorPpm;    // ppm dell'ancora (scala firmware); NaN se assente
    private final double anchorResponse; // response misurato all'ancora (scala firmware); NaN se assente

    private CalibrationCurve(boolean fitted, double a, double b, Double r2,
                            double validPpmMin, double validPpmMax,
                            Double sigmaLog, double anchorPpm, double anchorResponse) {
        this.fitted = fitted;
        this.a = a;
        this.b = b;
        this.r2 = r2;
        this.validPpmMin = validPpmMin;
        this.validPpmMax = validPpmMax;
        this.sigmaLog = sigmaLog;
        this.anchorPpm = anchorPpm;
        this.anchorResponse = anchorResponse;
    }

    /** Curva "non fittata": coefficienti assenti -> nessuna conversione disponibile. */
    public static CalibrationCurve unfitted() {
        return new CalibrationCurve(false, Double.NaN, Double.NaN, null, Double.NaN, Double.NaN,
                null, Double.NaN, Double.NaN);
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

            // dispersione log dei residui (ampiezza banda di stabilita'), opzionale
            Double sigmaLog = (n.hasNonNull("sigma_log") && n.get("sigma_log").asDouble() > 0)
                    ? n.get("sigma_log").asDouble() : null;

            // ancora alla scala firmware, opzionale: { anchor_ppm, anchor_response }
            double anchorPpm = Double.NaN, anchorResponse = Double.NaN;
            JsonNode anchor = n.get("anchor");
            if (anchor != null && anchor.isObject()) {
                if (anchor.hasNonNull("anchor_ppm")) {
                    anchorPpm = anchor.get("anchor_ppm").asDouble();
                }
                if (anchor.hasNonNull("anchor_response")) {
                    anchorResponse = anchor.get("anchor_response").asDouble();
                }
            }
            return new CalibrationCurve(true, a, b, r2, lo, hi, sigmaLog, anchorPpm, anchorResponse);
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
     * cade fuori da {@code valid_ppm}.
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

    // =====================================================================
    // Curva di stabilita' e ancoraggio alla scala firmware (V7)
    // =====================================================================

    /** Dispersione log10 dei residui del fit (meta'-ampiezza della banda), se presente. */
    public Optional<Double> sigmaLog() {
        return Optional.ofNullable(sigmaLog);
    }

    /** True se il JSON porta un'ancora certificata sulla scala firmware (response misurato a ppm noto). */
    public boolean hasCertifiedAnchor() {
        return fitted && Double.isFinite(anchorResponse) && anchorResponse > 0
                && Double.isFinite(anchorPpm) && anchorPpm > 0;
    }

    public OptionalDouble anchorPpm() {
        return Double.isFinite(anchorPpm) ? OptionalDouble.of(anchorPpm) : OptionalDouble.empty();
    }

    public OptionalDouble anchorResponse() {
        return (Double.isFinite(anchorResponse) && anchorResponse > 0)
                ? OptionalDouble.of(anchorResponse) : OptionalDouble.empty();
    }

    /**
     * Meta'-ampiezza della BANDA DI STABILITA' espressa come frazione (relativa) del response,
     * derivata dalla dispersione log: una deviazione entro {@code 10^(k*sigma_log)-1} (lato alto)
     * e' considerata fisiologica. E' una quantita' RELATIVA, quindi valida anche sulla scala
     * firmware [0,1] (auto-riferita). Vuoto se {@code sigma_log} non e' definito.
     *
     * @param kSigma numero di sigma (es. 3 per la banda d'azione)
     */
    public OptionalDouble stabilityBandFractionUpper(double kSigma) {
        if (!fitted || sigmaLog == null || !(kSigma > 0)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.pow(10.0, kSigma * sigmaLog) - 1.0);
    }

    /**
     * Conversione ASSOLUTA response->ppm ANCORATA, sulla scala del firmware e fisicamente
     * fondata: {@code ppm = anchorPpm * (response / anchorResponse)^(1/b)}.
     *
     * <p>Non usa mai l'intercetta {@code a} del paper (scala diversa): usa solo l'esponente
     * {@code b} (invariante di scala) e un punto di ancoraggio MISURATO sulla stessa scala
     * del {@code response} passato. L'ancora puo' essere la install baseline catturata a regime
     * (ppm = setpoint) oppure un riferimento certificato.
     *
     * @param response       response corrente (stessa scala dell'ancora, es. firmware [0,1])
     * @param anchorResponse response misurato all'ancora (>0)
     * @param anchorPpm      concentrazione nota all'ancora (>0)
     * @return ppm stimati, oppure vuoto se input non validi o curva non fittata
     */
    public OptionalDouble ppmFromAnchoredResponse(double response, double anchorResponse, double anchorPpm) {
        if (!fitted || !(response > 0) || !(anchorResponse > 0) || !(anchorPpm > 0)) {
            return OptionalDouble.empty();
        }
        double ppm = anchorPpm * Math.pow(response / anchorResponse, 1.0 / b);
        return Double.isFinite(ppm) ? OptionalDouble.of(ppm) : OptionalDouble.empty();
    }

    /**
     * Errore di concentrazione implicato dal drift del response rispetto alla baseline misurata
     * al setpoint: {@code ppmImplied - setpoint}, con {@code ppmImplied} dalla conversione ancorata
     * (anchor = baseline @ setpoint). Positivo = il sensore implica piu' CO2 del vero (sovrastima),
     * quindi l'offset correttivo additivo da inviare e' l'opposto del valore restituito.
     *
     * @param response   response corrente (scala firmware)
     * @param baseline   install baseline catturata a regime al setpoint (scala firmware, >0)
     * @param setpointPpm concentrazione vera di riferimento (es. 50000)
     */
    public OptionalDouble impliedSetpointErrorPpm(double response, double baseline, double setpointPpm) {
        OptionalDouble implied = ppmFromAnchoredResponse(response, baseline, setpointPpm);
        return implied.isPresent()
                ? OptionalDouble.of(implied.getAsDouble() - setpointPpm)
                : OptionalDouble.empty();
    }
}
