CREATE TABLE IF NOT EXISTS local_adventure_hangup (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    map_id INTEGER NOT NULL,
    credited_at BIGINT NOT NULL,
    pending_seconds INTEGER NOT NULL CHECK (pending_seconds >= 0),
    coins BIGINT NOT NULL CHECK (coins >= 0),
    role_exp BIGINT NOT NULL CHECK (role_exp >= 0),
    elf_exp BIGINT NOT NULL CHECK (elf_exp >= 0),
    elf_pool BIGINT NOT NULL CHECK (elf_pool >= 0)
);

CREATE TABLE IF NOT EXISTS local_adventure_map_reward (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    map_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, map_id)
);

CREATE TABLE IF NOT EXISTS local_adventure_quicken (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(70) NOT NULL,
    reward_day VARCHAR(10) NOT NULL,
    PRIMARY KEY (role_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_adventure_quicken_day ON local_adventure_quicken (role_id, reward_day);

CREATE TABLE IF NOT EXISTS local_adventure_drop_cursor (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    settled_seconds BIGINT NOT NULL CHECK (settled_seconds >= 0)
);

CREATE TABLE IF NOT EXISTS local_adventure_fast (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    reward_day VARCHAR(10) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_adventure_fast_day ON local_adventure_fast(role_id, reward_day);

ALTER TABLE local_adventure_hangup ADD COLUMN IF NOT EXISTS dungeon_group INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE local_adventure_hangup ADD COLUMN IF NOT EXISTS dungeon_seconds INTEGER DEFAULT 0 NOT NULL;

CREATE TABLE IF NOT EXISTS local_hangup_action (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(100) NOT NULL,
    PRIMARY KEY(role_id, receipt)
);
