CREATE TABLE IF NOT EXISTS local_elf_follow (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    position INTEGER NOT NULL CHECK (position >= 1 AND position <= 3),
    elf_id VARCHAR(40) NOT NULL REFERENCES local_elf(id),
    credited_at BIGINT NOT NULL CHECK (credited_at >= 0),
    PRIMARY KEY (role_id, position),
    UNIQUE (role_id, elf_id)
);

CREATE TABLE IF NOT EXISTS local_elf_follow_action (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    request_key VARCHAR(72) NOT NULL,
    PRIMARY KEY (role_id, request_key)
);

