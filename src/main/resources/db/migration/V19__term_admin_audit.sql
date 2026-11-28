CREATE TABLE term_admin_audit (
    audit_id BINARY(16) PRIMARY KEY,
    term_version_id BINARY(16) NOT NULL,
    actor_id BINARY(16),
    action VARCHAR(16) NOT NULL CHECK (action IN ('CREATE', 'PUBLICATION')),
    reason VARCHAR(200) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY (term_version_id) REFERENCES term_version(term_version_id),
    FOREIGN KEY (actor_id) REFERENCES member(member_id) ON DELETE SET NULL
);
CREATE INDEX ix_term_admin_audit_version ON term_admin_audit(term_version_id, occurred_at, audit_id);
