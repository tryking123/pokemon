CREATE TABLE IF NOT EXISTS local_adventure_progress (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    next_mission INT NOT NULL CHECK (next_mission >= 10001)
);

CREATE TABLE IF NOT EXISTS local_level_gift_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    tier_index INT NOT NULL CHECK (tier_index > 0),
    PRIMARY KEY (role_id, cycle_start, tier_index)
);

CREATE TABLE IF NOT EXISTS local_adventure_equipment (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    config_id INT NOT NULL,
    slot INT NOT NULL CHECK (slot BETWEEN 1 AND 6),
    UNIQUE (role_id, config_id)
);

CREATE TABLE IF NOT EXISTS local_adventure_equipment_instance (
    id VARCHAR(36) PRIMARY KEY,
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    config_id INTEGER NOT NULL,
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 6)
);

INSERT INTO local_adventure_equipment_instance (id, role_id, config_id, slot)
SELECT old.id, old.role_id, old.config_id, old.slot
FROM local_adventure_equipment old
WHERE NOT EXISTS (SELECT 1 FROM local_adventure_equipment_instance current_gear WHERE current_gear.id = old.id);

CREATE TABLE IF NOT EXISTS local_trainer_equipped (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 6),
    equipment_id VARCHAR(36) NOT NULL UNIQUE REFERENCES local_adventure_equipment_instance(id),
    enhance_level INTEGER NOT NULL CHECK (enhance_level BETWEEN 0 AND 150),
    PRIMARY KEY (role_id, slot)
);

CREATE TABLE IF NOT EXISTS local_trainer_melt (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    points INTEGER NOT NULL CHECK (points >= 0)
);

CREATE TABLE IF NOT EXISTS local_trainer_attributes (
    equipment_id VARCHAR(36) PRIMARY KEY REFERENCES local_adventure_equipment_instance(id) ON DELETE CASCADE,
    attributes VARCHAR(2048) NOT NULL
);

CREATE TABLE IF NOT EXISTS local_trainer_refine_pending (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    equipment_id VARCHAR(36) NOT NULL REFERENCES local_adventure_equipment_instance(id) ON DELETE CASCADE,
    preview_key VARCHAR(96) NOT NULL,
    attributes VARCHAR(2048) NOT NULL
);

CREATE TABLE IF NOT EXISTS local_trainer_refine_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(96) NOT NULL,
    PRIMARY KEY(role_id,request_key)
);

CREATE TABLE IF NOT EXISTS local_trainer_forge (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    offer_id VARCHAR(36) NOT NULL,
    config_id INTEGER NOT NULL,
    day_number BIGINT NOT NULL,
    refreshes INTEGER NOT NULL CHECK (refreshes >= 0)
);

CREATE TABLE IF NOT EXISTS local_trainer_forge_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(96) NOT NULL,
    PRIMARY KEY(role_id,request_key)
);

ALTER TABLE local_adventure_equipment_instance ADD COLUMN IF NOT EXISTS advanced INTEGER DEFAULT 0 NOT NULL;
