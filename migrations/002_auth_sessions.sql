-- Auth tables for Go Blink API (persisted OTP + sessions).

CREATE TABLE IF NOT EXISTS blink_otp_challenge (
    email VARCHAR(255) PRIMARY KEY,
    code_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS blink_session (
    token_hash VARCHAR(128) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_blink_session_email ON blink_session (email);
CREATE INDEX IF NOT EXISTS idx_blink_session_expires ON blink_session (expires_at);
