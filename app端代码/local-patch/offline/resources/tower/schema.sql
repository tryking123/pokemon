CREATE TABLE IF NOT EXISTS local_tower_state (
 role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
 state_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_tower_clear (
 role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
 layer INTEGER NOT NULL,
 finished BIGINT NOT NULL,
 PRIMARY KEY(role_id,layer)
);
CREATE TABLE IF NOT EXISTS local_tower_receipt (
 role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
 receipt VARCHAR(96) NOT NULL,
 reply_json TEXT NOT NULL,
 PRIMARY KEY(role_id,receipt)
);

CREATE TABLE IF NOT EXISTS local_tower_layer (layer INTEGER PRIMARY KEY,cleared INTEGER NOT NULL);
