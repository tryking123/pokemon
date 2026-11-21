CREATE TABLE IF NOT EXISTS local_mystery_shop (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    generation BIGINT NOT NULL,
    refresh_at BIGINT NOT NULL,
    counter_day VARCHAR(10) NOT NULL,
    item_refreshes INTEGER NOT NULL,
    diamond_refreshes INTEGER NOT NULL,
    purchased_mask INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS local_mystery_shop_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(70) NOT NULL,
    selected_slot INTEGER NOT NULL,
    PRIMARY KEY (role_id, request_id)
);
