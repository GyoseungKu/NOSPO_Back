-- Existing accounts are unchanged. NULL personal fields are only used for retained withdrawals.
ALTER TABLE member MODIFY COLUMN nickname VARCHAR(20) NULL;
ALTER TABLE member MODIFY COLUMN nickname_key VARCHAR(20) NULL;
ALTER TABLE member MODIFY COLUMN phone_number VARCHAR(16) NULL;
ALTER TABLE member ADD COLUMN withdrawal_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE member ADD COLUMN withdrawn_at DATETIME(6);
ALTER TABLE member ADD COLUMN recoverable_until DATETIME(6);
ALTER TABLE account MODIFY COLUMN email VARCHAR(254) NULL;
ALTER TABLE account MODIFY COLUMN email_key VARCHAR(254) NULL;

CREATE TABLE withdrawal_operation (
    operation_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    generation BIGINT NOT NULL,
    session_id BINARY(16) NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    withdrawn_at DATETIME(6) NOT NULL,
    recoverable_until DATETIME(6) NOT NULL,
    UNIQUE(member_id,generation),
    UNIQUE(member_id,idempotency_key),
    FOREIGN KEY(member_id) REFERENCES member(member_id) ON DELETE RESTRICT,
    CHECK(status IN ('RETAINED','RESTORED','PURGING'))
);
CREATE INDEX ix_withdrawal_deadline ON withdrawal_operation(status,recoverable_until);
CREATE TABLE withdrawal_archive (
    operation_id BINARY(16) PRIMARY KEY,
    format_version INT NOT NULL,
    payload_ciphertext LONGBLOB NOT NULL,
    wrapped_data_key BLOB NOT NULL,
    wrapping_key_id VARCHAR(128) NOT NULL,
    FOREIGN KEY(operation_id) REFERENCES withdrawal_operation(operation_id) ON DELETE CASCADE
);
-- HMAC identifiers are personal data too: stage 2 must delete them at the deadline.
CREATE TABLE withdrawal_identity (
    kind VARCHAR(16) NOT NULL,
    identifier_hash BINARY(32) NOT NULL,
    operation_id BINARY(16) NOT NULL,
    PRIMARY KEY(kind,identifier_hash),
    FOREIGN KEY(operation_id) REFERENCES withdrawal_operation(operation_id) ON DELETE CASCADE,
    CHECK(kind IN ('EMAIL','KAKAO'))
);
CREATE TABLE withdrawal_target (
    target_id BINARY(16) PRIMARY KEY,
    operation_id BINARY(16) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    locator_ciphertext BLOB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token BINARY(16),
    lease_until DATETIME(6),
    FOREIGN KEY(operation_id) REFERENCES withdrawal_operation(operation_id) ON DELETE CASCADE,
    CHECK(status IN ('PENDING','PROCESSING','COMPLETED'))
);
CREATE INDEX ix_withdrawal_target_ready ON withdrawal_target(status,next_attempt_at,lease_until);
