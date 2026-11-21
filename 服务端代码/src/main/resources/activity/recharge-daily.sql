CREATE TABLE IF NOT EXISTS local_daily_recharge_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    business_day VARCHAR(10) NOT NULL,
    tier INTEGER NOT NULL,
    PRIMARY KEY (role_id, period_start, business_day, tier)
);

CREATE TABLE IF NOT EXISTS local_daily_recharge_exchange (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(70) NOT NULL,
    exchange_index INTEGER NOT NULL,
    PRIMARY KEY (role_id, request_id)
);

CREATE TABLE IF NOT EXISTS local_elf_chip (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    config_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (role_id, config_id)
);
