CREATE TABLE social_identity (
    social_identity_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(128) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_social_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uq_social_member_provider UNIQUE (member_id, provider),
    CONSTRAINT ck_social_provider CHECK (provider = 'KAKAO'),
    FOREIGN KEY (member_id) REFERENCES account(member_id) ON DELETE RESTRICT
);
