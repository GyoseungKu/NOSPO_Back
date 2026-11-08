CREATE TABLE member (
    member_id BINARY(16) PRIMARY KEY,
    nickname VARCHAR(20) NOT NULL,
    nickname_key VARCHAR(20) NOT NULL UNIQUE,
    phone_number VARCHAR(16) NOT NULL,
    profile_image_key VARCHAR(512),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
CREATE TABLE account (
    member_id BINARY(16) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    email_key VARCHAR(254) NOT NULL UNIQUE,
    email_verified_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE TABLE password_credential (
    member_id BINARY(16) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE CASCADE
);
CREATE TABLE auth_session (
    session_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    revoke_reason VARCHAR(40),
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE CASCADE
);
CREATE INDEX ix_session_member ON auth_session(member_id,revoked_at);
CREATE TABLE refresh_token (
    refresh_token_id BINARY(16) PRIMARY KEY,
    session_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL UNIQUE,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    revoked_at DATETIME(6),
    replaced_by_token_id BINARY(16) UNIQUE,
    FOREIGN KEY (session_id) REFERENCES auth_session(session_id) ON DELETE CASCADE,
    FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_token(refresh_token_id) ON DELETE SET NULL
);
CREATE TABLE term (
    term_id BINARY(16) PRIMARY KEY,
    type VARCHAR(24) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL
);
CREATE TABLE term_version (
    term_version_id BINARY(16) PRIMARY KEY,
    term_id BINARY(16) NOT NULL,
    version VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content LONGTEXT NOT NULL,
    required BOOLEAN NOT NULL,
    publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    effective_at DATETIME(6),
    requires_reconsent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (term_id,version),
    UNIQUE (term_id,effective_at),
    FOREIGN KEY (term_id) REFERENCES term(term_id) ON DELETE RESTRICT
);
CREATE TABLE member_consent (
    consent_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    term_version_id BINARY(16) NOT NULL,
    agreed BOOLEAN NOT NULL,
    agreed_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    FOREIGN KEY (term_version_id) REFERENCES term_version(term_version_id) ON DELETE RESTRICT
);
CREATE TABLE auth_challenge (
    challenge_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16),
    session_id BINARY(16),
    purpose VARCHAR(32) NOT NULL,
    target_email VARCHAR(254) NOT NULL,
    target_email_key VARCHAR(254) NOT NULL,
    secret_digest BINARY(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    resend_available_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6),
    invalidated_at DATETIME(6),
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE RESTRICT,
    FOREIGN KEY (session_id) REFERENCES auth_session(session_id) ON DELETE RESTRICT
);
CREATE INDEX ix_challenge_digest ON auth_challenge(secret_digest);
CREATE INDEX ix_challenge_target ON auth_challenge(target_email_key,purpose,created_at);
CREATE INDEX ix_challenge_owner ON auth_challenge(member_id);
CREATE INDEX ix_challenge_expiry ON auth_challenge(expires_at);
CREATE TABLE auth_proof (
    proof_id BINARY(16) PRIMARY KEY,
    challenge_id BINARY(16) UNIQUE,
    member_id BINARY(16),
    session_id BINARY(16),
    purpose VARCHAR(40) NOT NULL,
    proof_hash BINARY(32) NOT NULL UNIQUE,
    target_email VARCHAR(254),
    target_email_key VARCHAR(254),
    provider VARCHAR(20),
    provider_subject VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    invalidated_at DATETIME(6),
    FOREIGN KEY (challenge_id) REFERENCES auth_challenge(challenge_id) ON DELETE RESTRICT,
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE RESTRICT,
    FOREIGN KEY (session_id) REFERENCES auth_session(session_id) ON DELETE RESTRICT
);
CREATE INDEX ix_proof_expiry ON auth_proof(expires_at);
CREATE INDEX ix_proof_owner ON auth_proof(member_id);
CREATE TABLE email_delivery (
    delivery_id BINARY(16) PRIMARY KEY,
    challenge_id BINARY(16) NOT NULL UNIQUE,
    member_id BINARY(16),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payload_ciphertext LONGBLOB,
    payload_key_id VARCHAR(128),
    payload_expires_at DATETIME(6) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6),
    lease_token BINARY(16),
    lease_until DATETIME(6),
    cancellation_requested_at DATETIME(6),
    last_error_code VARCHAR(64),
    accepted_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (challenge_id) REFERENCES auth_challenge(challenge_id) ON DELETE RESTRICT,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE RESTRICT,
    CHECK (status IN ('PENDING','PROCESSING','ACCEPTED','FAILED','CANCELLED')),
    CHECK (status NOT IN ('ACCEPTED','FAILED','CANCELLED') OR (payload_ciphertext IS NULL AND payload_key_id IS NULL))
);
CREATE INDEX ix_delivery_ready ON email_delivery(status,next_attempt_at,lease_until);
CREATE INDEX ix_delivery_owner ON email_delivery(member_id);
CREATE INDEX ix_delivery_expiry ON email_delivery(payload_expires_at);
CREATE TABLE idempotency_record (
    idempotency_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16),
    scope_key VARCHAR(160) NOT NULL,
    http_method VARCHAR(8) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_status SMALLINT,
    response_ciphertext LONGBLOB,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE (scope_key,http_method,request_path,idempotency_key),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE INDEX ix_idempotency_expiry ON idempotency_record(expires_at);
CREATE TABLE credit_wallet (
    member_id BINARY(16) PRIMARY KEY,
    balance INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    CHECK (balance>=0 AND reserved>=0 AND reserved<=balance),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE TABLE credit_transaction (
    transaction_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    amount INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    -- This initial ledger supports signup rewards only. Later transaction kinds require a separate migration.
    CHECK (kind='SIGNUP' AND amount=5),
    UNIQUE (member_id,kind),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
-- Infrastructure rows contain HMAC scope keys, never plaintext email/IP/token values.
-- Expiring rows serialize even the first request across application instances.
CREATE TABLE auth_guard (
    scope_hash BINARY(32) PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL
);
CREATE INDEX ix_guard_expiry ON auth_guard(expires_at);
CREATE TABLE auth_rate_limit (
    scope_hash BINARY(32) PRIMARY KEY,
    request_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL
);
CREATE INDEX ix_rate_expiry ON auth_rate_limit(expires_at);
