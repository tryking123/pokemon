CREATE TABLE IF NOT EXISTS local_card_daily_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    paysid INTEGER NOT NULL,
    business_day VARCHAR(10) NOT NULL,
    PRIMARY KEY (role_id, paysid, business_day)
);

CREATE TABLE IF NOT EXISTS local_fund_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    tier INTEGER NOT NULL,
    PRIMARY KEY (role_id, tier)
);

CREATE TABLE IF NOT EXISTS local_speed_card_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    business_day VARCHAR(10) NOT NULL,
    activated_at BIGINT NOT NULL,
    PRIMARY KEY (role_id, business_day)
);
