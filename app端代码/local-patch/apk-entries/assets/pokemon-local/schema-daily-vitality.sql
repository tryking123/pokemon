CREATE TABLE IF NOT EXISTS local_vitality_event (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    task_id INTEGER NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    occurred_at BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (role_id, task_id, source_key)
);
CREATE INDEX IF NOT EXISTS ix_local_vitality_time ON local_vitality_event(role_id, occurred_at);

CREATE TABLE IF NOT EXISTS local_vitality_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    day_start BIGINT NOT NULL,
    tier INTEGER NOT NULL,
    PRIMARY KEY (role_id, day_start, tier)
);
