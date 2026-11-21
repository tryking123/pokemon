CREATE TABLE IF NOT EXISTS local_dailydup_state (
 role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
 state_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_dailydup_receipt (
 role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
 receipt VARCHAR(90) NOT NULL,
 reply_json TEXT NOT NULL,
 PRIMARY KEY(role_id,receipt)
);
CREATE TABLE IF NOT EXISTS local_dailydup_completion (
 role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
 receipt VARCHAR(90) NOT NULL,
 stage_id INTEGER NOT NULL,
 swept INTEGER NOT NULL,
 completed_at BIGINT NOT NULL,
 PRIMARY KEY(role_id,receipt)
);
CREATE INDEX IF NOT EXISTS local_dailydup_completion_time ON local_dailydup_completion(role_id,completed_at);

CREATE TABLE IF NOT EXISTS local_dailydup_recovery (
 role_id VARCHAR(36) NOT NULL, play_day BIGINT NOT NULL, coins BIGINT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0,
 receipt VARCHAR(100), PRIMARY KEY(role_id,play_day)
);
