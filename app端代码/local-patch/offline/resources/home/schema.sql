CREATE TABLE IF NOT EXISTS local_home_state (role_id VARCHAR(100) PRIMARY KEY, level INTEGER NOT NULL, mood BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS local_home_receipt (role_id VARCHAR(100) NOT NULL, receipt VARCHAR(128) NOT NULL, PRIMARY KEY(role_id,receipt));
CREATE TABLE IF NOT EXISTS local_orange_state(role_id VARCHAR(100) PRIMARY KEY, state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_relic_state(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_relic_rank(role_id VARCHAR(100) NOT NULL,week_id BIGINT NOT NULL,best_seconds INTEGER NOT NULL,PRIMARY KEY(role_id,week_id));
CREATE TABLE IF NOT EXISTS local_home_operation(role_id VARCHAR(100) NOT NULL, receipt VARCHAR(128) NOT NULL, reply TEXT NOT NULL, PRIMARY KEY(role_id,receipt));
CREATE TABLE IF NOT EXISTS local_home_breeding(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_home_exam(role_id VARCHAR(100) PRIMARY KEY, state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_link_state(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_link_rank(role_id VARCHAR(100) NOT NULL,week_id BIGINT NOT NULL,steps INTEGER NOT NULL,PRIMARY KEY(role_id,week_id));

CREATE TABLE IF NOT EXISTS local_home_building(role_id VARCHAR(100) NOT NULL,slot INTEGER NOT NULL,sid INTEGER NOT NULL,last_at BIGINT NOT NULL,PRIMARY KEY(role_id,slot));
CREATE TABLE IF NOT EXISTS local_home_race(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS local_home_race_rank(role_id VARCHAR(100) NOT NULL,week_id BIGINT NOT NULL,distance INTEGER NOT NULL,PRIMARY KEY(role_id,week_id));
