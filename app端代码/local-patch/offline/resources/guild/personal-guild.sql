CREATE TABLE IF NOT EXISTS local_personal_guild (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL,
    guild_name VARCHAR(64) NOT NULL,
    intro VARCHAR(256) NOT NULL,
    notice VARCHAR(256) NOT NULL,
    xp BIGINT NOT NULL DEFAULT 0,
    auto_audit BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    closed_at BIGINT,
    FOREIGN KEY (role_id) REFERENCES local_game_role(id)
);

CREATE INDEX IF NOT EXISTS ix_personal_guild_owner ON local_personal_guild(role_id, closed_at);

CREATE TABLE IF NOT EXISTS local_personal_guild_receipt (
    role_id VARCHAR(36) NOT NULL,
    receipt VARCHAR(96) NOT NULL,
    guild_id VARCHAR(36) NOT NULL,
    action_name VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (role_id, receipt),
    FOREIGN KEY (guild_id) REFERENCES local_personal_guild(id)
);

CREATE TABLE IF NOT EXISTS local_personal_guild_donation (
    role_id VARCHAR(36) NOT NULL,
    guild_id VARCHAR(36) NOT NULL,
    business_day BIGINT NOT NULL,
    receipt VARCHAR(96) NOT NULL,
    created_at BIGINT NOT NULL,
    contribution INTEGER NOT NULL,
    PRIMARY KEY (role_id, receipt),
    FOREIGN KEY (guild_id) REFERENCES local_personal_guild(id)
);

CREATE INDEX IF NOT EXISTS ix_personal_guild_donation_day ON local_personal_guild_donation(role_id, business_day);
ALTER TABLE local_personal_guild_donation ADD COLUMN IF NOT EXISTS currency VARCHAR(16) NOT NULL DEFAULT 'coins';
ALTER TABLE local_personal_guild_donation ADD COLUMN IF NOT EXISTS vitality INTEGER NOT NULL DEFAULT 10;

CREATE TABLE IF NOT EXISTS local_personal_guild_purchase (
    role_id VARCHAR(36) NOT NULL,
    receipt VARCHAR(96) NOT NULL,
    reset_period BIGINT NOT NULL,
    offer_id INTEGER NOT NULL,
    bundles INTEGER NOT NULL,
    spent BIGINT NOT NULL,
    PRIMARY KEY (role_id, receipt),
    FOREIGN KEY (role_id) REFERENCES local_game_role(id)
);

CREATE INDEX IF NOT EXISTS ix_personal_guild_purchase_period ON local_personal_guild_purchase(role_id, reset_period);
