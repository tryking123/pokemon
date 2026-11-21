CREATE TABLE IF NOT EXISTS local_first_recharge_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    order_id VARCHAR(36) NOT NULL UNIQUE REFERENCES local_game_payment_order(id),
    paysid INTEGER NOT NULL CHECK (paysid BETWEEN 8 AND 11),
    PRIMARY KEY (role_id, period_start)
);
