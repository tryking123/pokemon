CREATE TABLE IF NOT EXISTS local_wish_well (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    wish_day BIGINT NOT NULL,
    choices INTEGER NOT NULL,
    claimed INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (role_id, wish_day)
);
