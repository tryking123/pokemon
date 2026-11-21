CREATE TABLE IF NOT EXISTS local_space_progress (
 role_id VARCHAR(100) PRIMARY KEY, mission_id INTEGER NOT NULL, state_json TEXT NOT NULL
);
