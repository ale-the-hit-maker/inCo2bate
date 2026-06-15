-- =====================================================================
-- V6 - Finalizzazione autocal per incubatore CO2
--
-- Allinea installazioni gia' migrate a V5 con:
--   * semantica OFFSET_CAL = offset additivo assoluto in ppm;
--   * valid_ppm operativo incubatore [30000, 70000] attorno al setpoint
--     5% CO2 = 50.000 ppm;
--   * range sperimentale paper mantenuto in experimental_ppm [250, 5000];
--   * metadata di stabilita'/aging fondati sui paper disponibili.
-- =====================================================================

UPDATE drift_reference_curves
SET points_json = jsonb_build_object(
        'model',            'power_law',
        'fit_space',        'log10',
        'equation',         'response = a*ppm^b  (log10(response)=alpha+beta*log10(ppm))',
        'alpha',            0.345,
        'alpha_sigma',      0.051,
        'beta',             0.254,
        'beta_sigma',       0.019,
        'a',                2.2131,
        'b',                0.254,
        'r2',               0.98,
        'valid_ppm',        jsonb_build_array(30000, 70000),
        'experimental_ppm', jsonb_build_array(250, 5000),
        'ref_temp_c',       250,
        'ref_env',          jsonb_build_object('temp_c', 37, 'rh_pct', 95),
        'stability_days',   7,
        'aging_model',      jsonb_build_object(
                                'model', 'observed_linear_drift_guardrail',
                                'validated_days', 7,
                                'eol_drift_pct', 20,
                                'notes', 'J Mater Chem A 2026: stable 7-day response at 250C with slight late decrease; platform projects EOL from observed drift.'
                            ),
        'response_scale',   'paper_conductance_ratio',
        'source',           'Rossi et al., ACS Appl. Mater. Interfaces 2023 (15, 33732); J. Mater. Chem. A 2026, 14, 6457-6469; supp. info centralina CO2',
        'notes',            'alpha/beta are a log10 allometric fit: a=10^alpha, b=beta. The published experimental range is 250-5000 ppm; the production validity range is the incubator operating window 30000-70000 ppm around 5% CO2. Use b for drift-to-ppm guardrails; do not apply a directly to firmware sensor_response [0,1].'
    ),
    ref_temp_c = 250
WHERE sensor_type = 'NaIn2O3_CO2';
