CREATE TABLE IF NOT EXISTS local_dojo_progress (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    history INTEGER NOT NULL DEFAULT 0,
    dojo_id INTEGER NOT NULL DEFAULT 1001,
    tactics INTEGER NOT NULL DEFAULT 0,
    defeated_mask INTEGER NOT NULL DEFAULT 0,
    attempt_day BIGINT NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0,
    practices INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS local_dojo_team (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    position INTEGER NOT NULL,
    battle_id INTEGER NOT NULL,
    elf_id VARCHAR(40) NOT NULL REFERENCES local_elf(id),
    PRIMARY KEY (role_id, position),
    UNIQUE (role_id, elf_id),
    UNIQUE (role_id, battle_id)
);

CREATE TABLE IF NOT EXISTS local_elf_aid (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    slot INTEGER NOT NULL,
    elf_id VARCHAR(40) NOT NULL REFERENCES local_elf(id),
    PRIMARY KEY (role_id, slot),
    UNIQUE (role_id, elf_id)
);

CREATE TABLE IF NOT EXISTS local_dojo_attempt (
    role_id VARCHAR(36) PRIMARY KEY REFERENCES local_game_role(id),
    attempt_id VARCHAR(32) NOT NULL,
    dojo_id INTEGER NOT NULL,
    group_index INTEGER NOT NULL,
    seed INTEGER NOT NULL,
    practice BOOLEAN NOT NULL,
    defeated_mask INTEGER NOT NULL,
    started_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS local_dojo_sweep (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    dojo_id INTEGER NOT NULL,
    sweep_day BIGINT NOT NULL,
    sweep_count INTEGER NOT NULL CHECK (sweep_count > 0),
    PRIMARY KEY (role_id, dojo_id, sweep_day)
);

CREATE TABLE IF NOT EXISTS local_dojo_challenge_day (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    challenge_day BIGINT NOT NULL,
    PRIMARY KEY (role_id, challenge_day)
);

CREATE TABLE IF NOT EXISTS local_dojo_station (
    realm_id INTEGER NOT NULL,
    dojo_id INTEGER NOT NULL,
    role_id VARCHAR(36) NOT NULL UNIQUE REFERENCES local_game_role(id),
    token VARCHAR(36) NOT NULL,
    start_at BIGINT NOT NULL,
    end_at BIGINT NOT NULL,
    defender CLOB NOT NULL,
    PRIMARY KEY(realm_id,dojo_id)
);
CREATE TABLE IF NOT EXISTS local_dojo_station_lock(id INTEGER PRIMARY KEY);
INSERT INTO local_dojo_station_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM local_dojo_station_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS local_dojo_station_state(
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    state_key VARCHAR(96) NOT NULL,
    payload CLOB NOT NULL,
    PRIMARY KEY(role_id,state_key)
);
