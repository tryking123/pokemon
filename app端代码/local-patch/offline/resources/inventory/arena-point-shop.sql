CREATE TABLE IF NOT EXISTS local_arena_point_purchase (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(72) NOT NULL,
    product_id INTEGER NOT NULL CHECK (product_id > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    points BIGINT NOT NULL CHECK (points > 0),
    PRIMARY KEY (role_id, request_key)
);


CREATE TABLE IF NOT EXISTS local_dojo_point_purchase (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(72) NOT NULL,
    product_id INTEGER NOT NULL CHECK (product_id > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    points BIGINT NOT NULL CHECK (points > 0),
    PRIMARY KEY (role_id, request_key)
);
