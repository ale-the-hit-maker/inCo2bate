-- =====================================================================
-- V7 - Curva di stabilita' per incubatore CO2 (banda + ancoraggio scala firmware)
--
-- Estende il seed Na:In2O3 (gia' fittato in V5/V6) con cio' che serve per:
--   1) una CURVA DI STABILITA' esplicita attorno al setpoint 5% CO2 (50.000 ppm),
--      espressa come banda di tolleranza sul drift del response (auto-riferito);
--   2) l'ANCORAGGIO della curva alla scala del firmware (sensor_response in [0,1]),
--      che rende l'esponente allometrico b utilizzabile per una stima ASSOLUTA di
--      ppm/offset senza usare l'intercetta 'a' del paper (scala diversa);
--   3) la dispersione log (sigma_log) che definisce l'ampiezza "fisiologica" della banda;
--   4) i range di CO2 accettabili per coltura cellulare (qualita'/allarme/operativo).
--
-- Tutto ADDITIVO: aggiorna solo il seed gia' presente (sensor_type=NaIn2O3_CO2);
-- nessun impatto su misure o altri dati. Idempotente.
--
-- NOTA SCIENTIFICA (perche' NON estrapoliamo l'intercetta assoluta a 50k):
--   * b (esponente) e la dispersione log-log sono INVARIANTI di scala -> usabili a 50k.
--   * 'a' e' legata alla "response" del paper (rapporto di conduttanza ~4..13), diversa
--     dal sensor_response del firmware [0,1]. L'intercetta sulla scala firmware (a_fw) NON
--     e' costante: si ricava ancorando la curva a un punto MISURATO al setpoint
--     (di default la install baseline catturata a regime; oppure un riferimento certificato).
--   * Cosi' la conversione assoluta response->ppm = anchor_ppm * (resp/anchor_resp)^(1/b)
--     non usa mai 'a' ed e' fisicamente fondata (vedi docs/DRIFT_detection_finale.md).
-- =====================================================================

UPDATE drift_reference_curves
SET points_json = (points_json::jsonb || jsonb_build_object(

        -- Dispersione log dei residui del fit: meta' ampiezza della banda di stabilita'.
        -- Scelta in modo che la banda +/-3*sigma_log coincida con la soglia d'azione 5%
        -- (log10(1.05) = 0.0212 ~= 3*0.0071). DA VALIDARE con i residui del Dataset A.
        'sigma_log', 0.0071,

        -- CURVA DI STABILITA' (auto-riferita): bande sul |drift| del response a regime
        -- rispetto alla baseline di installazione. Coerente con policy e dashboard.
        'stability_band', jsonb_build_object(
            'space',            'self_referenced_response_drift',
            'k_sigma',          3,
            'stable_max_pct',   5,    -- |drift| < 5%  -> STABILE (entro banda fisiologica ~3 sigma)
            'action_min_pct',   5,    -- 5% <= |drift| < 20% -> DRIFTING (correggibile)
            'eol_pct',          20,   -- |drift| >= 20% -> fine vita (sostituzione)
            'max_drift_rate_pct_per_day', 3,  -- oltre -> anomalia di processo, NON drift (Dataset B)
            'notes', 'Banda attorno alla baseline MISURATA al setpoint; il rate-of-change separa drift (giorni/settimane) da un calo reale di CO2 (minuti/ore).'
        ),

        -- ANCORAGGIO alla scala firmware: rende b utilizzabile per ppm/offset assoluti.
        -- anchor_response=null -> usa la install baseline (catturata a regime) come ancora.
        -- Inserire un valore solo se misurato con strumento certificato al setpoint.
        'anchor', jsonb_build_object(
            'response_scale_target', 'firmware_sensor_response_0_1',
            'anchor_ppm',            50000,
            'anchor_response',       null,
            'anchor_source',         'install_baseline_at_regime',
            'notes', 'a_fw = anchor_response / anchor_ppm^b. Con anchor=install baseline, la curva assoluta coincide con la banda auto-riferita; un anchor certificato indipendente la rende assoluta vera.'
        ),

        -- RANGE CO2 ACCETTABILI per coltura cellulare mammifera (incubatore).
        -- setpoint 5% CO2 = 50.000 ppm (pH fisiologico 7.2-7.4 con terreni bicarbonato).
        'incubator_co2', jsonb_build_object(
            'setpoint_ppm',   50000,                       -- 5% CO2 (standard)
            'quality_ppm',    jsonb_build_array(45000, 55000),  -- 4.5-5.5%: pH ottimale per la maggior parte dei terreni
            'operating_ppm',  jsonb_build_array(30000, 70000),  -- 3-7%: banda operativa/allarme (allineata ad AlertService)
            'tolerated_ppm',  jsonb_build_array(40000, 100000), -- 4-10%: tollerato, dipende dal terreno
            'notes', 'Standard 5% CO2; 4-10% comune secondo il terreno. Allarme piattaforma fuori 3-7% (30k-70k).'
        )
    ))
WHERE sensor_type = 'NaIn2O3_CO2';
