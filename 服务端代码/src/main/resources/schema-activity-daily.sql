CREATE TABLE IF NOT EXISTS local_online_clock (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    online_seconds INTEGER NOT NULL CHECK (online_seconds >= 0),
    counted_until BIGINT NOT NULL,
    PRIMARY KEY (role_id, cycle_start)
);

CREATE TABLE IF NOT EXISTS local_online_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    stage INTEGER NOT NULL CHECK (stage BETWEEN 1 AND 5),
    receipt VARCHAR(70) NOT NULL,
    PRIMARY KEY (role_id, cycle_start, stage),
    UNIQUE (role_id, cycle_start, receipt)
);

CREATE TABLE IF NOT EXISTS local_attendance_day (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    day_index INTEGER NOT NULL CHECK (day_index BETWEEN 1 AND 30),
    PRIMARY KEY (role_id, cycle_start, day_index)
);

ALTER TABLE local_attendance_day ADD COLUMN IF NOT EXISTS patch_receipt VARCHAR(70);

CREATE UNIQUE INDEX IF NOT EXISTS idx_local_attendance_patch_receipt
    ON local_attendance_day (role_id, patch_receipt);

CREATE TABLE IF NOT EXISTS local_attendance_claim (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    reward_index INTEGER NOT NULL CHECK (reward_index BETWEEN 1 AND 5),
    PRIMARY KEY (role_id, cycle_start, reward_index)
);

CREATE TABLE IF NOT EXISTS local_attendance_vip (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    cycle_start BIGINT NOT NULL,
    reward_index INTEGER NOT NULL CHECK (reward_index BETWEEN 1 AND 30),
    PRIMARY KEY (role_id,cycle_start,reward_index)
);

CREATE TABLE IF NOT EXISTS local_stamina_ledger (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    receipt VARCHAR(80) NOT NULL,
    day_start BIGINT NOT NULL,
    delta INTEGER NOT NULL CHECK (delta IN (-100,100)),
    PRIMARY KEY (role_id, receipt)
);
CREATE INDEX IF NOT EXISTS idx_stamina_role_day ON local_stamina_ledger(role_id, day_start, delta);
