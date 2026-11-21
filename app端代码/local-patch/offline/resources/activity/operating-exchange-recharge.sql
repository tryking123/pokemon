CREATE TABLE IF NOT EXISTS local_operating_activity_action (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    activity_id INTEGER NOT NULL,
    period_start BIGINT NOT NULL,
    action_index INTEGER NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, activity_id, period_start, action_index, request_key)
);
CREATE TABLE IF NOT EXISTS local_turntable_event (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(64) NOT NULL,
    period_start BIGINT NOT NULL,
    event_day INTEGER NOT NULL,
    action_index INTEGER NOT NULL,
    wheel_slot INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    amount INTEGER NOT NULL,
    remaining INTEGER NOT NULL,
    points_after INTEGER NOT NULL,
    occurred_at BIGINT NOT NULL,
    PRIMARY KEY(role_id,request_key)
);
CREATE INDEX IF NOT EXISTS ix_local_turntable_round ON local_turntable_event(period_start,role_id,action_index);
CREATE TABLE IF NOT EXISTS local_turntable_settlement (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    rank_no INTEGER NOT NULL,
    PRIMARY KEY(role_id,period_start)
);
CREATE INDEX IF NOT EXISTS ix_local_operating_activity_request
    ON local_operating_activity_action (role_id, activity_id, request_key);

CREATE TABLE IF NOT EXISTS local_pika_progress (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    current_layer INTEGER NOT NULL,
    total_attempts INTEGER NOT NULL,
    layer_attempts INTEGER NOT NULL,
    guaranteed_at INTEGER NOT NULL,
    PRIMARY KEY(role_id,period_start)
);

CREATE TABLE IF NOT EXISTS local_myth_shop (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    shop_type INTEGER NOT NULL,
    period_start BIGINT NOT NULL,
    batch BIGINT NOT NULL,
    revision INTEGER NOT NULL,
    fee_day BIGINT NOT NULL,
    refresh_count INTEGER NOT NULL,
    shelf_level INTEGER NOT NULL,
    sold_mask INTEGER NOT NULL,
    PRIMARY KEY(role_id,shop_type)
);

CREATE TABLE IF NOT EXISTS local_diamond_sale (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    lot INTEGER NOT NULL,
    committed INTEGER NOT NULL,
    historical INTEGER NOT NULL,
    spent INTEGER NOT NULL,
    PRIMARY KEY(role_id,period_start)
);

CREATE TABLE IF NOT EXISTS local_smash_egg (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    hammer_count INTEGER NOT NULL,
    rare_ids VARCHAR(256) NOT NULL,
    mailed_rank INTEGER NOT NULL,
    PRIMARY KEY(role_id,period_start)
);

CREATE TABLE IF NOT EXISTS local_carnival_progress (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    task_type INTEGER NOT NULL,
    event_day BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    PRIMARY KEY(role_id, task_type, event_day)
);
