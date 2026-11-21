CREATE TABLE IF NOT EXISTS local_home_casino (
    role_id VARCHAR(36) NOT NULL PRIMARY KEY REFERENCES local_game_role(id),
    state_data CLOB NOT NULL
);
