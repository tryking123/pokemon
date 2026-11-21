CREATE TABLE IF NOT EXISTS local_hunting_day (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    day_number BIGINT NOT NULL,
    pool_json VARCHAR(512) NOT NULL
);
CREATE TABLE IF NOT EXISTS local_hunting_session (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    day_number BIGINT NOT NULL,
    state_json VARCHAR(512) NOT NULL
);
CREATE TABLE IF NOT EXISTS local_hunting_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(90) NOT NULL,
    reply_json VARCHAR(256) NOT NULL,
    PRIMARY KEY (role_id, receipt)
);
