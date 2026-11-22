CREATE TABLE IF NOT EXISTS local_eden_state (
 role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
 state_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_eden_receipt (
 role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
 receipt VARCHAR(96) NOT NULL,
 reply_json TEXT NOT NULL,
 PRIMARY KEY (role_id,receipt)
);
