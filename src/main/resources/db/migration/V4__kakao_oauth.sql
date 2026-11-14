CREATE TABLE oauth_flow (
    flow_id BINARY(16) PRIMARY KEY,
    state_hash BINARY(32) NOT NULL UNIQUE,
    cookie_hash BINARY(32) NOT NULL,
    member_id BINARY(16),
    session_id BINARY(16),
    return_path VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE RESTRICT,
    FOREIGN KEY (session_id) REFERENCES auth_session(session_id) ON DELETE RESTRICT
);
CREATE INDEX ix_oauth_flow_expiry ON oauth_flow(expires_at);
CREATE TABLE oauth_exchange (
    exchange_id BINARY(16) PRIMARY KEY,
    flow_id BINARY(16) NOT NULL UNIQUE,
    code_hash BINARY(32) NOT NULL UNIQUE,
    payload_ciphertext BLOB,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    FOREIGN KEY (flow_id) REFERENCES oauth_flow(flow_id) ON DELETE RESTRICT
);
CREATE INDEX ix_oauth_exchange_expiry ON oauth_exchange(expires_at);
CREATE TABLE social_signup_context (
    proof_id BINARY(16) PRIMARY KEY,
    flow_id BINARY(16) NOT NULL,
    payload_ciphertext BLOB NOT NULL,
    FOREIGN KEY (proof_id) REFERENCES auth_proof(proof_id) ON DELETE RESTRICT,
    FOREIGN KEY (flow_id) REFERENCES oauth_flow(flow_id) ON DELETE RESTRICT
);
