CREATE TABLE IF NOT EXISTS local_profile_cleanup_receipt (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_id VARCHAR(128) NOT NULL,
    kind INTEGER NOT NULL CHECK (kind BETWEEN 1 AND 3),
    PRIMARY KEY(role_id, request_id)
);
