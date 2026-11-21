CREATE TABLE IF NOT EXISTS local_bag_expansion (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    bag VARCHAR(10) NOT NULL CHECK (bag IN ('ordinary', 'elf')),
    request_key VARCHAR(72) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    PRIMARY KEY (role_id, bag, request_key)
);

