CREATE TABLE IF NOT EXISTS local_activity_login_day (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    day_number INTEGER NOT NULL CHECK (day_number BETWEEN 1 AND 7),
    PRIMARY KEY (role_id, period_start, day_number)
);

CREATE TABLE IF NOT EXISTS local_activity_login_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    reward_number INTEGER NOT NULL CHECK (reward_number BETWEEN 1 AND 7),
    PRIMARY KEY (role_id, period_start, reward_number)
);

CREATE TABLE IF NOT EXISTS local_activity_login_dress (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    dress_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, dress_id)
);
