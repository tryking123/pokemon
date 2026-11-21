CREATE TABLE IF NOT EXISTS local_game_role (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES local_account(id),
    realm_id INTEGER NOT NULL,
    name VARCHAR(48) NOT NULL,
    sex INTEGER NOT NULL CHECK (sex IN (0, 1)),
    level INTEGER NOT NULL DEFAULT 1,
    exp BIGINT NOT NULL DEFAULT 0,
    coins BIGINT NOT NULL DEFAULT 10000,
    diamonds BIGINT NOT NULL DEFAULT 0,
    scene_id INTEGER NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (account_id, realm_id),
    UNIQUE (realm_id, name)
);

ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS return_scene_id INTEGER;
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS return_x INTEGER;
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS return_y INTEGER;
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS stage INTEGER NOT NULL DEFAULT 1 CHECK (stage BETWEEN 0 AND 2);
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS rename_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS avatar INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS local_diamond_spend (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    spent_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_local_diamond_spend_role_time ON local_diamond_spend(role_id, spent_at);

CREATE TABLE IF NOT EXISTS local_role_avatar (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    avatar_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, avatar_id)
);

CREATE TABLE IF NOT EXISTS local_role_story (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    chapter INTEGER NOT NULL DEFAULT 0,
    starter_selected BOOLEAN NOT NULL DEFAULT FALSE,
    tutorial_won BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS local_role_task (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    task_id INTEGER NOT NULL,
    progress INTEGER NOT NULL CHECK (progress >= 0),
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    claimed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_id, task_id)
);

CREATE TABLE IF NOT EXISTS local_role_story_clear (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    stage_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, stage_id)
);

CREATE TABLE IF NOT EXISTS local_role_story_metric (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    metric INTEGER NOT NULL,
    progress INTEGER NOT NULL CHECK (progress >= 0),
    PRIMARY KEY (role_id, metric)
);

CREATE TABLE IF NOT EXISTS local_exp_recovery (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    business_day BIGINT NOT NULL,
    choice INTEGER NOT NULL,
    experience BIGINT NOT NULL,
    PRIMARY KEY(role_id, business_day)
);
ALTER TABLE local_game_role ADD COLUMN IF NOT EXISTS dojo_points BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS local_dynamax_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(64) NOT NULL,
    PRIMARY KEY(role_id,request_key)
);

CREATE TABLE IF NOT EXISTS local_passport_metric (
    role_id VARCHAR(100) NOT NULL,
    event_day BIGINT NOT NULL,
    kind INTEGER NOT NULL,
    quantity BIGINT NOT NULL,
    PRIMARY KEY(role_id,event_day,kind)
);
