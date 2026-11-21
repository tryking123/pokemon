CREATE TABLE IF NOT EXISTS local_inventory_item (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    item_id INTEGER NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0 AND quantity <= 2147483647),
    position INTEGER NOT NULL,
    PRIMARY KEY (role_id, item_id),
    UNIQUE (role_id, position)
);

CREATE TABLE IF NOT EXISTS local_system_mail (
    id VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    subject VARCHAR(128) NOT NULL,
    body VARCHAR(4096) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    opened BOOLEAN NOT NULL DEFAULT FALSE,
    claimed BOOLEAN NOT NULL DEFAULT FALSE,
    coins BIGINT NOT NULL DEFAULT 0 CHECK (coins >= 0),
    diamonds BIGINT NOT NULL DEFAULT 0 CHECK (diamonds >= 0),
    item_id INTEGER NOT NULL DEFAULT 0 CHECK (item_id >= 0),
    quantity BIGINT NOT NULL DEFAULT 0 CHECK (quantity >= 0)
);

CREATE TABLE IF NOT EXISTS local_system_mail_reward (
    mail_id VARCHAR(64) NOT NULL REFERENCES local_system_mail(id),
    ordinal INTEGER NOT NULL,
    reward_type INTEGER NOT NULL CHECK (reward_type IN (1, 2)),
    config_id INTEGER NOT NULL CHECK (config_id > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (mail_id, ordinal)
);

ALTER TABLE local_system_mail ADD COLUMN IF NOT EXISTS wallet_claimed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE local_system_mail ADD COLUMN IF NOT EXISTS claimed_quantity BIGINT NOT NULL DEFAULT 0;
ALTER TABLE local_system_mail_reward ADD COLUMN IF NOT EXISTS claimed_quantity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE local_system_mail_reward ADD COLUMN IF NOT EXISTS asset_type INTEGER;

CREATE TABLE IF NOT EXISTS local_mail_claim_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(72) NOT NULL,
    completed_ids VARCHAR(14000) NOT NULL,
    PRIMARY KEY (role_id, request_key)
);

CREATE TABLE IF NOT EXISTS local_inventory_grant_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    source_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_id, source_key)
);

CREATE TABLE IF NOT EXISTS local_shop_purchase (
    role_id VARCHAR(64) NOT NULL REFERENCES local_game_role(id),
    shop INTEGER NOT NULL,
    period_start BIGINT NOT NULL,
    product_id INTEGER NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (role_id, shop, period_start, product_id)
);

CREATE TABLE IF NOT EXISTS local_inventory_currency (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    currency INTEGER NOT NULL,
    balance BIGINT NOT NULL CHECK (balance >= 0),
    PRIMARY KEY (role_id, currency)
);
ALTER TABLE local_system_mail ADD COLUMN IF NOT EXISTS dojo_points BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS local_trainer_sticker (
 role_id VARCHAR(72) NOT NULL, sticker_id INTEGER NOT NULL, amount INTEGER NOT NULL, locked BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(role_id,sticker_id)
);
CREATE TABLE IF NOT EXISTS local_trainer_engraving (
 role_id VARCHAR(72) NOT NULL, slot INTEGER NOT NULL, position_no INTEGER NOT NULL, set_id INTEGER NOT NULL,
 PRIMARY KEY(role_id,slot,position_no)
);
CREATE TABLE IF NOT EXISTS local_trainer_rune_shop (
 role_id VARCHAR(72) PRIMARY KEY, chips INTEGER NOT NULL DEFAULT 0, period BIGINT NOT NULL, day_no BIGINT NOT NULL,
 manual INTEGER NOT NULL, revision BIGINT NOT NULL, shelf TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_trainer_rune_receipt (
 role_id VARCHAR(72) NOT NULL, receipt VARCHAR(128) NOT NULL, reply TEXT NOT NULL, PRIMARY KEY(role_id,receipt)
);
