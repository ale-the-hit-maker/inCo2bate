CREATE TABLE IF NOT EXISTS notification_contacts (
    id          BIGSERIAL PRIMARY KEY,
    lab_id      BIGINT       NOT NULL REFERENCES labs(id) ON DELETE CASCADE,
    label       VARCHAR(120) NOT NULL,
    channel     VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    target      VARCHAR(512) NOT NULL,
    min_level   VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_contacts_lab ON notification_contacts(lab_id);

CREATE TABLE IF NOT EXISTS alerts (
    id          BIGSERIAL PRIMARY KEY,
    hub_id      BIGINT       NOT NULL REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    level       VARCHAR(16)  NOT NULL,
    rule_key    VARCHAR(64)  NOT NULL,
    message     VARCHAR(512) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_alerts_hub_time ON alerts(hub_id, created_at DESC);
