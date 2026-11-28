ALTER TABLE wiki_submission ADD COLUMN review_comment VARCHAR(5000);
ALTER TABLE wiki_submission ADD COLUMN reward_amount INT;
ALTER TABLE wiki_submission ADD COLUMN reward_reason VARCHAR(1000);
ALTER TABLE wiki_submission ADD COLUMN reward_transaction_id BINARY(16);
ALTER TABLE wiki_submission ADD COLUMN published_version INT;
ALTER TABLE wiki_submission ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uq_wiki_submission_reward ON wiki_submission(reward_transaction_id);

CREATE TABLE wiki_request (
    actor_id BINARY(16) NOT NULL,
    scope VARCHAR(100) NOT NULL,
    request_key BINARY(16) NOT NULL,
    draft_id BINARY(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    response MEDIUMTEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id, scope, request_key),
    FOREIGN KEY (actor_id) REFERENCES member(member_id) ON DELETE CASCADE,
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(draft_id) ON DELETE CASCADE
);

CREATE TABLE wiki_activity (
    activity_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    draft_id BINARY(16),
    submission_id BINARY(16),
    action VARCHAR(20) NOT NULL,
    actor_id BINARY(16),
    comment VARCHAR(5000),
    reward_amount INT,
    reward_reason VARCHAR(1000),
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_id) REFERENCES member(member_id) ON DELETE SET NULL
);
CREATE INDEX ix_wiki_activity_member ON wiki_activity(member_id, occurred_at, activity_id);
CREATE INDEX ix_wiki_activity_submission ON wiki_activity(submission_id, occurred_at, activity_id);

CREATE TABLE wiki_revision_draft (
    submission_id BINARY(16) PRIMARY KEY,
    draft_id BINARY(16) NOT NULL UNIQUE,
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(draft_id) ON DELETE CASCADE,
    FOREIGN KEY (submission_id) REFERENCES wiki_submission(submission_id) ON DELETE CASCADE
);

CREATE TABLE wiki_asset (
    asset_id BINARY(16) PRIMARY KEY,
    book_id BINARY(16) NOT NULL,
    draft_id BINARY(16),
    width INT NOT NULL,
    height INT NOT NULL,
    image_bytes MEDIUMBLOB NOT NULL,
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(draft_id) ON DELETE CASCADE
);
