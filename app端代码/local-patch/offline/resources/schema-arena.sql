CREATE TABLE IF NOT EXISTS local_arena_state (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    state_key VARCHAR(128) NOT NULL,
    state_data CLOB NOT NULL,
    PRIMARY KEY (role_id, state_key)
);

CREATE TABLE IF NOT EXISTS local_world_boss (
    realm_id INTEGER NOT NULL,
    mode_id INTEGER NOT NULL,
    week_id BIGINT NOT NULL,
    state_data CLOB NOT NULL,
    PRIMARY KEY (realm_id, mode_id, week_id)
);
