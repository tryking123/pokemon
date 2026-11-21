CREATE TABLE IF NOT EXISTS local_christmas_tree (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    entry_key INTEGER NOT NULL,
    entry_value INTEGER NOT NULL,
    PRIMARY KEY (role_id, period_start, entry_key)
);

CREATE TABLE IF NOT EXISTS local_christmas_elf_quote (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    offer_index INTEGER NOT NULL,
    period_start BIGINT NOT NULL,
    token VARCHAR(36) NOT NULL,
    snapshot VARCHAR(36) NOT NULL,
    PRIMARY KEY (role_id, offer_index)
);
