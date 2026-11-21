CREATE TABLE IF NOT EXISTS local_game_payment_order (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    paysid INTEGER NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    reward BIGINT NOT NULL CONSTRAINT local_game_payment_reward_valid CHECK (reward >= 0),
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    confirmed_at BIGINT,
    notified_at BIGINT
);

CREATE INDEX IF NOT EXISTS local_game_payment_role
    ON local_game_payment_order(role_id, paysid, confirmed_at);

CREATE TABLE IF NOT EXISTS local_classic_first_charge_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    PRIMARY KEY (role_id, period_start)
);

CREATE TABLE IF NOT EXISTS local_vip_gift_purchase (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    vip_level INTEGER NOT NULL CHECK (vip_level BETWEEN 0 AND 17),
    PRIMARY KEY (role_id, vip_level)
);

CREATE TABLE IF NOT EXISTS local_vip_gift_carry (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    config_id INTEGER NOT NULL,
    star INTEGER NOT NULL
);

ALTER TABLE local_vip_gift_carry ADD COLUMN IF NOT EXISTS migrated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS local_timed_shop_claim (
    order_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_payment_order(id),
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(80) NOT NULL,
    UNIQUE(role_id,request_key)
);
CREATE TABLE IF NOT EXISTS local_luxury_gift_order (
    order_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_payment_order(id),
    receipt VARCHAR(80) UNIQUE
);
DROP VIEW IF EXISTS local_recharge_activity_order;
CREATE TABLE IF NOT EXISTS local_item_vip_grant (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(100) NOT NULL,
    item_id INTEGER NOT NULL,
    quantity BIGINT NOT NULL,
    vip_experience BIGINT NOT NULL,
    recharge_amount BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(role_id, receipt)
);
CREATE VIEW local_recharge_activity_order AS
    SELECT o.* FROM local_game_payment_order o WHERE o.paysid NOT BETWEEN 301 AND 306 AND o.paysid NOT IN (2001,2002)
    AND NOT EXISTS (SELECT 1 FROM local_luxury_gift_order g WHERE g.order_id=o.id)
    UNION ALL SELECT id,role_id,31008 AS paysid,recharge_amount AS amount,0 AS reward,
        created_at,created_at AS expires_at,created_at AS confirmed_at,created_at AS notified_at
        FROM local_item_vip_grant WHERE item_id=31008;
