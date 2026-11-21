CREATE TABLE IF NOT EXISTS local_guild_encounter_state (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    state_key VARCHAR(96) NOT NULL,
    payload CLOB NOT NULL,
    PRIMARY KEY(role_id,state_key)
);
