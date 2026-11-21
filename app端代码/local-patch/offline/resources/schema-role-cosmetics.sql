CREATE TABLE IF NOT EXISTS local_role_cosmetic (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    equipped_title INTEGER NOT NULL DEFAULT 0,
    displayed_title INTEGER NOT NULL DEFAULT 0,
    worn_dress INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS local_role_cosmetic_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_id, request_id)
);
