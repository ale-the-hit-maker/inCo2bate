CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS sensing_hubs (
    id BIGSERIAL PRIMARY KEY,
    hub_key VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS measurements (
    hub_id BIGINT NOT NULL REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    co2_ppm DOUBLE PRECISION NOT NULL,
    heater_temp DOUBLE PRECISION NOT NULL,
    env_temp DOUBLE PRECISION NOT NULL,
    env_hum DOUBLE PRECISION NOT NULL,
    rail_12v DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (hub_id, recorded_at)
);

SELECT create_hypertable('measurements', 'recorded_at', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS idx_measurements_recorded_at_desc ON measurements (recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_measurements_hub_recorded_at_desc ON measurements (hub_id, recorded_at DESC);
