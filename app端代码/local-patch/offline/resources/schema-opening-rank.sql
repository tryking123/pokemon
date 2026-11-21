CREATE TABLE IF NOT EXISTS local_opening_rank_cursor (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    activity_id INTEGER NOT NULL,
    next_end BIGINT NOT NULL,
    PRIMARY KEY (role_id, activity_id)
);
