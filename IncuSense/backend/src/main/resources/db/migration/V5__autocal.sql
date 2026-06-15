-- =====================================================================
-- V5 — Auto-calibrazione semi-real-time (loop chiuso piattaforma -> nodo)
--
-- Due cose, entrambe ADDITIVE e retrocompatibili (vedi docs/AUTOCAL_v1_design.md):
--   1) lifecycle degli eventi di calibrazione (tabella calibration_events);
--   2) popolamento dei coefficienti della curva di riferimento Na:In2O3
--      con la regressione lineare (log-log) fornita dall'owner.
--
-- Nessun impatto sui dati esistenti: tabella nuova + UPDATE del solo seed
-- non ancora fittato (a IS NULL).
-- =====================================================================

-- 1) Lifecycle eventi di calibrazione --------------------------------------------------
CREATE TABLE IF NOT EXISTS calibration_events (
    id                   BIGSERIAL PRIMARY KEY,
    hub_id               BIGINT      NOT NULL REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    command              VARCHAR(16) NOT NULL,                    -- OFFSET_CAL | ZERO_CAL
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING|ACKED|VERIFIED|FAILED|SKIPPED
    requested_offset_ppm DOUBLE PRECISION,                        -- target ppm inviato al nodo (semantica OFFSET_CAL)
    drift_pct_at_request DOUBLE PRECISION,                        -- drift osservato al momento della richiesta
    reason               VARCHAR(255),                            -- motivo (azione o guard-rail che ha bloccato)
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    acked_at             TIMESTAMPTZ,
    verified_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_calibration_events_hub
    ON calibration_events (hub_id, created_at DESC);

-- 2) Coefficienti della curva di riferimento Na:In2O3 ----------------------------------
-- Modello: response = a * ppm^b  <=>  log10(response) = alpha + beta*log10(ppm)
-- Regressione (owner):  alpha = 0.345 +/- 0.051 (intercetta, base 10)
--                       beta  = 0.254 +/- 0.019 (pendenza = esponente b)
--                       R^2   = 0.98
--   => a = 10^alpha = 2.2131 ,  b = beta = 0.254
--
-- ATTENZIONE / nota di contratto (§6 unita'):
--   * b (esponente) e la dispersione log-log sono INVARIANTI di scala: usabili dalla
--     piattaforma a prescindere dalla normalizzazione del "response".
--   * a (intercetta) e' legata alla definizione di response del paper (rapporto di
--     conduttanza ~4..13), DIVERSA dal sensor_response del firmware (|I_air-I|/I_air in [0,1]).
--     Percio' la piattaforma NON converte automaticamente il sensor_response grezzo in ppm
--     con questa 'a'. responseToPpm vale solo per input sulla stessa scala della curva.
--   * valid_ppm = [250, 5000] (range del fit). NON estrapolabile al setpoint incubatore (30k-50k).
UPDATE drift_reference_curves
SET points_json = jsonb_build_object(
        'model',          'power_law',
        'fit_space',      'log10',
        'equation',       'response = a*ppm^b  (log10(response)=alpha+beta*log10(ppm))',
        'alpha',          0.345,
        'alpha_sigma',    0.051,
        'beta',           0.254,
        'beta_sigma',     0.019,
        'a',              2.2131,
        'b',              0.254,
        'r2',             0.98,
        'valid_ppm',      jsonb_build_array(250, 5000),
        'ref_temp_c',     250,
        'ref_env',        jsonb_build_object('temp_c', 37, 'rh_pct', 95),
        'stability_weeks',7,
        'response_scale', 'paper_conductance_ratio',
        'source',         'Rossi et al., ACS Appl. Mater. Interfaces 2023 (15, 33732) + supp. info centralina; regressione alpha/beta fornita dall''owner',
        'notes',          'Fit su range 250-5000 ppm; non estrapolabile al setpoint incubatore. a e'' su scala paper, diversa dal sensor_response firmware [0,1]: non applicare a al response grezzo.'
    ),
    ref_temp_c = 250
WHERE sensor_type = 'NaIn2O3_CO2'
  AND (points_json->>'a') IS NULL;
