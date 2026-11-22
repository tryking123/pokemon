CREATE TABLE IF NOT EXISTS local_elf (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    id VARCHAR(40) PRIMARY KEY,
    config_id INTEGER NOT NULL,
    level INTEGER NOT NULL DEFAULT 1,
    team INTEGER NOT NULL DEFAULT 1,
    slot INTEGER NOT NULL DEFAULT 1,
    starter BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE local_elf ADD COLUMN IF NOT EXISTS starter BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE local_elf ADD COLUMN IF NOT EXISTS training_data VARCHAR(8192) NOT NULL DEFAULT '{}';

CREATE TABLE IF NOT EXISTS local_elf_gear (
    id VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(64) NOT NULL REFERENCES local_game_role(id),
    kind INTEGER NOT NULL,
    config_id INTEGER NOT NULL,
    elf_id VARCHAR(64),
    position INTEGER NOT NULL DEFAULT 0,
    protected INTEGER NOT NULL DEFAULT 0,
    star INTEGER NOT NULL DEFAULT 0,
    details VARCHAR(4096) NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_local_elf_gear_role_kind ON local_elf_gear(role_id, kind);

CREATE TABLE IF NOT EXISTS local_pstone_refine_pending (
    role_id VARCHAR(64) PRIMARY KEY REFERENCES local_game_role(id),
    gear_id VARCHAR(64) NOT NULL REFERENCES local_elf_gear(id) ON DELETE CASCADE,
    attributes VARCHAR(2048) NOT NULL
);

CREATE TABLE IF NOT EXISTS local_pstone_refine_receipt (
    role_id VARCHAR(64) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(96) NOT NULL,
    PRIMARY KEY(role_id,receipt)
);

INSERT INTO local_elf_gear (id, role_id, kind, config_id, star)
SELECT legacy.id, legacy.role_id, 4, legacy.config_id, legacy.star
FROM local_vip_gift_carry legacy
WHERE legacy.migrated=FALSE AND NOT EXISTS (SELECT 1 FROM local_elf_gear gear WHERE gear.id=legacy.id);

UPDATE local_vip_gift_carry SET migrated=TRUE WHERE migrated=FALSE;
CREATE INDEX IF NOT EXISTS local_elf_owner_idx ON local_elf(role_id, starter);

CREATE TABLE IF NOT EXISTS local_elf_handbook (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    config_id INTEGER NOT NULL,
    unread INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (role_id, config_id)
);

CREATE TABLE IF NOT EXISTS local_battle_clear (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    encounter INTEGER NOT NULL,
    cleared_at BIGINT NOT NULL,
    PRIMARY KEY (role_id, encounter)
);

CREATE TABLE IF NOT EXISTS local_battle_attempt (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    encounter INTEGER NOT NULL,
    seed INTEGER NOT NULL,
    defeated_mask INTEGER NOT NULL DEFAULT 0,
    started_at BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVE', 'WON', 'FLED'))
);

CREATE TABLE IF NOT EXISTS local_pstone_wallet (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    debris BIGINT NOT NULL CHECK (debris >= 0)
);

CREATE TABLE IF NOT EXISTS local_pstone_shop (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    generation BIGINT NOT NULL,
    next_refresh BIGINT NOT NULL,
    counter_day BIGINT NOT NULL,
    manual_count INTEGER NOT NULL,
    goods VARCHAR(100) NOT NULL,
    sold INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS local_party_formation (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    form_type INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS local_formation_growth (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    form_type INTEGER NOT NULL,
    form_level INTEGER NOT NULL,
    form_exp INTEGER NOT NULL,
    PRIMARY KEY(role_id,form_type)
);

CREATE TABLE IF NOT EXISTS local_formation_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(80) NOT NULL,
    form_level INTEGER NOT NULL,
    form_exp INTEGER NOT NULL,
    PRIMARY KEY(role_id,request_id)
);

CREATE TABLE IF NOT EXISTS local_dungeon_sweep (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(80) NOT NULL,
    dungeon_id INTEGER NOT NULL,
    settled_at BIGINT NOT NULL,
    item_id INTEGER NOT NULL,
    PRIMARY KEY(role_id,request_key)
);
CREATE INDEX IF NOT EXISTS idx_dungeon_sweep_day ON local_dungeon_sweep(role_id,settled_at);

CREATE TABLE IF NOT EXISTS local_elf_individual_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(80) NOT NULL,
    PRIMARY KEY(role_id,receipt)
);

-- 进阶、进化与突破请求回执，PC 与 Android 随启动共用同一建表入口。
CREATE TABLE IF NOT EXISTS local_elf_growth_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(128) NOT NULL,
    signature VARCHAR(2048) NOT NULL,
    result_json CLOB NOT NULL,
    PRIMARY KEY(role_id,receipt)
);

CREATE TABLE IF NOT EXISTS local_elf_pcl_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(96) NOT NULL,
    payload CLOB NOT NULL,
    PRIMARY KEY(role_id,receipt)
);
CREATE TABLE IF NOT EXISTS local_nest_state(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS local_dungeon_purchase (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(80) NOT NULL,
    day_start BIGINT NOT NULL,
    price INTEGER NOT NULL,
    PRIMARY KEY(role_id,request_key)
);
CREATE INDEX IF NOT EXISTS idx_dungeon_purchase_day ON local_dungeon_purchase(role_id,day_start);

CREATE TABLE IF NOT EXISTS local_secret_stone (
    sid VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(100) NOT NULL,
    template_id INTEGER NOT NULL
);
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS locked INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS stars INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS attrs TEXT;
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS candidate TEXT;
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS lucky INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE local_secret_stone ADD COLUMN IF NOT EXISTS elf_id VARCHAR(40);
CREATE UNIQUE INDEX IF NOT EXISTS idx_secret_stone_worn ON local_secret_stone(role_id,elf_id);

CREATE TABLE IF NOT EXISTS local_team_choice (
    role_id VARCHAR(100) NOT NULL, purpose VARCHAR(30) NOT NULL, team_value INTEGER NOT NULL,
    PRIMARY KEY(role_id,purpose)
);

CREATE TABLE IF NOT EXISTS local_dungeon_fighter_life (
    role_id VARCHAR(100) NOT NULL, encounter_group INTEGER NOT NULL, elf_id VARCHAR(100) NOT NULL,
    current_hp INTEGER NOT NULL, max_hp INTEGER NOT NULL, PRIMARY KEY(role_id,encounter_group,elf_id)
);

CREATE TABLE IF NOT EXISTS local_elf_fusion_receipt (
    role_id VARCHAR(100) NOT NULL, request_key VARCHAR(120) NOT NULL, action_signature VARCHAR(500) NOT NULL,
    PRIMARY KEY(role_id,request_key)
);

CREATE TABLE IF NOT EXISTS local_elf_carry_inherit (
    role_id VARCHAR(64) NOT NULL, receipt VARCHAR(96) NOT NULL,
    source_elf VARCHAR(64) NOT NULL, target_elf VARCHAR(64) NOT NULL,
    PRIMARY KEY(role_id,receipt)
);

CREATE TABLE IF NOT EXISTS local_elf_rebirth (
    role_id VARCHAR(64) NOT NULL, receipt VARCHAR(96) NOT NULL,
    elf_id VARCHAR(64) NOT NULL, mode_value INTEGER NOT NULL,
    PRIMARY KEY(role_id,receipt)
);

CREATE TABLE IF NOT EXISTS local_gold_carry_receipt (
    role_id VARCHAR(64) NOT NULL, receipt VARCHAR(96) NOT NULL, argument TEXT NOT NULL,
    result_value INTEGER NOT NULL, PRIMARY KEY(role_id,receipt)
);
