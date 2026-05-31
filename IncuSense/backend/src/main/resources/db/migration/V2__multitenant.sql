CREATE TABLE IF NOT EXISTS labs (
    id            BIGSERIAL PRIMARY KEY,
    lab_id        VARCHAR(64)  NOT NULL UNIQUE,
    display_name  VARCHAR(160) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(128) NOT NULL UNIQUE,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'LAB_USER',
    lab_id        BIGINT       NOT NULL REFERENCES labs(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_users_lab ON users(lab_id);

ALTER TABLE sensing_hubs ADD COLUMN IF NOT EXISTS lab_id BIGINT REFERENCES labs(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_hubs_lab ON sensing_hubs(lab_id);

-- Default lab for backward compatibility with already-ingested data and current firmware (slug 'lab_alpha')
INSERT INTO labs (lab_id, display_name) VALUES ('lab_alpha', 'Default Lab (migrated)')
    ON CONFLICT (lab_id) DO NOTHING;
UPDATE sensing_hubs SET lab_id = (SELECT id FROM labs WHERE lab_id='lab_alpha') WHERE lab_id IS NULL;
