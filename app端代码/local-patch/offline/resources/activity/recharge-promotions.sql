CREATE TABLE IF NOT EXISTS local_recharge_promotion_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    activity_id INTEGER NOT NULL,
    period_start BIGINT NOT NULL,
    tier INTEGER NOT NULL,
    PRIMARY KEY (role_id, activity_id, period_start, tier)
);
