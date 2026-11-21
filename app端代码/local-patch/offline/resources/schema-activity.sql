CREATE TABLE IF NOT EXISTS local_activity_calendar (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    starts_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS local_pumpkin_round (
    role_id VARCHAR(36) NOT NULL REFERENCES local_game_role(id),
    period_start BIGINT NOT NULL,
    floor INTEGER NOT NULL,
    jackpot INTEGER NOT NULL,
    seed BIGINT NOT NULL,
    opened INTEGER NOT NULL,
    PRIMARY KEY (role_id, period_start)
);

CREATE TABLE IF NOT EXISTS local_lucky_task(role_id VARCHAR(100) NOT NULL,period_start BIGINT NOT NULL,day_no INTEGER NOT NULL,task_id INTEGER NOT NULL,claimed INTEGER NOT NULL,PRIMARY KEY(role_id,period_start,day_no,task_id));
CREATE TABLE IF NOT EXISTS local_lucky_bag(role_id VARCHAR(100) NOT NULL,receipt VARCHAR(128) NOT NULL,period_start BIGINT NOT NULL,diamonds INTEGER NOT NULL,PRIMARY KEY(role_id,receipt));

CREATE TABLE IF NOT EXISTS local_guild_run (
 role_id VARCHAR(72) PRIMARY KEY, state_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_guild_run_rank (
 role_id VARCHAR(72) NOT NULL, period_day BIGINT NOT NULL, realm_id INTEGER NOT NULL,
 guild_name VARCHAR(128) NOT NULL, player_name VARCHAR(128) NOT NULL, best INTEGER NOT NULL,
 contribution INTEGER NOT NULL DEFAULT 0, settled INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(role_id,period_day)
);
