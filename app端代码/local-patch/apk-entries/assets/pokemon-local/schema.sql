CREATE TABLE IF NOT EXISTS local_account (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS local_session (
    token_digest VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES local_account(id),
    expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS local_session_expiry ON local_session(expires_at);

CREATE TABLE IF NOT EXISTS local_payment_order (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES local_account(id),
    client_order_id VARCHAR(128) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    reward BIGINT NOT NULL CHECK (reward > 0),
    created_at BIGINT NOT NULL,
    confirmed_at BIGINT,
    UNIQUE (account_id, client_order_id)
);
