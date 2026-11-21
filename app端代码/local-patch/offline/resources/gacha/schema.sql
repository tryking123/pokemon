CREATE TABLE IF NOT EXISTS local_gacha_state (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    normal_day DATE,
    normal_used INTEGER NOT NULL CHECK (normal_used BETWEEN 0 AND 5),
    normal_last BIGINT NOT NULL CHECK (normal_last >= 0),
    normal_total INTEGER NOT NULL CHECK (normal_total >= 0),
    advanced_total INTEGER NOT NULL CHECK (advanced_total >= 0)
);

CREATE TABLE IF NOT EXISTS local_gacha_draw (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(70) NOT NULL,
    draw_type INTEGER NOT NULL,
    reward_type INTEGER NOT NULL,
    reward_id INTEGER NOT NULL,
    diamonds_spent INTEGER NOT NULL,
    coupon_spent BOOLEAN NOT NULL,
    committed_at BIGINT NOT NULL,
    PRIMARY KEY (role_id, request_id)
);

ALTER TABLE local_gacha_state ADD COLUMN IF NOT EXISTS advanced_free_day DATE;
ALTER TABLE local_gacha_state ADD COLUMN IF NOT EXISTS friend_points BIGINT DEFAULT 0 NOT NULL CHECK (friend_points >= 0);
ALTER TABLE local_gacha_state ADD COLUMN IF NOT EXISTS friend_total INTEGER DEFAULT 0 NOT NULL CHECK (friend_total >= 0);
ALTER TABLE local_gacha_draw ADD COLUMN IF NOT EXISTS reward_batch VARCHAR(255) DEFAULT '' NOT NULL;

CREATE TABLE IF NOT EXISTS local_gacha_friendship_grant (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    grant_key VARCHAR(40) NOT NULL,
    PRIMARY KEY (role_id, grant_key)
);

CREATE TABLE IF NOT EXISTS local_gacha_special (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    target_id INTEGER NOT NULL,
    luck INTEGER NOT NULL,
    refresh_day BIGINT NOT NULL,
    refresh_count INTEGER NOT NULL,
    PRIMARY KEY(role_id,period_start)
);
CREATE TABLE IF NOT EXISTS local_gacha_special_refresh (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(80) NOT NULL,
    PRIMARY KEY(role_id,request_key)
);
