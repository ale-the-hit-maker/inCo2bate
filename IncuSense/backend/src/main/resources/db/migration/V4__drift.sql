-- Raw telemetry needed for drift monitoring (nullable: backward compatible with old firmware)
ALTER TABLE measurements ADD COLUMN IF NOT EXISTS raw_adc INTEGER;
ALTER TABLE measurements ADD COLUMN IF NOT EXISTS sensor_response DOUBLE PRECISION;

CREATE TABLE IF NOT EXISTS drift_reference_curves (
    id             BIGSERIAL PRIMARY KEY,
    sensor_type    VARCHAR(64)  NOT NULL,
    ref_voltage_v  DOUBLE PRECISION,
    ref_temp_c     DOUBLE PRECISION,
    description    VARCHAR(512),
    points_json    JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sensor_health (
    hub_id              BIGINT PRIMARY KEY REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    curve_id            BIGINT REFERENCES drift_reference_curves(id),
    install_response    DOUBLE PRECISION,
    operating_hours     DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_response       DOUBLE PRECISION,
    observed_drift_pct  DOUBLE PRECISION,
    projected_eol_at    TIMESTAMPTZ,
    health_status       VARCHAR(16) NOT NULL DEFAULT 'OK',
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed reference curve for the Na:In2O3 CO2 sensor (power-law calibration; coefficients TBD from dataset).
INSERT INTO drift_reference_curves (sensor_type, ref_voltage_v, ref_temp_c, description, points_json)
VALUES ('NaIn2O3_CO2', NULL, 250,
        'Na:In2O3 chemoresistive CO2 sensor @250C. Power-law calibration response=a*ppm^b. Coefficients to be fitted from experimental dataset.',
        '{"model":"power_law","a":null,"b":null,"ref_temp_c":250,"valid_ppm":[250,5000],"stability_weeks":7}'::jsonb);
