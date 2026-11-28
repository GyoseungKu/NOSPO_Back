CREATE TABLE wiki (
    wiki_id BINARY(16) PRIMARY KEY,
    book_id BINARY(16) NOT NULL UNIQUE,
    UNIQUE (wiki_id, book_id)
);

CREATE TABLE wiki_version (
    wiki_id BINARY(16) NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    snapshot MEDIUMTEXT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (wiki_id, version),
    FOREIGN KEY (wiki_id) REFERENCES wiki(wiki_id) ON DELETE CASCADE
);

CREATE TABLE wiki_draft (
    draft_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    status VARCHAR(10) NOT NULL,
    position_unit VARCHAR(8) NOT NULL CHECK (position_unit IN ('PAGE', 'EPISODE')),
    position_value INT NOT NULL CHECK (position_value >= 0),
    wiki_id BINARY(16),
    base_wiki_version INT,
    parent_submission_id BINARY(16),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    snapshot MEDIUMTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CHECK ((mode = 'CREATE' AND wiki_id IS NULL AND base_wiki_version IS NULL)
        OR (mode = 'UPDATE' AND wiki_id IS NOT NULL AND base_wiki_version IS NOT NULL)),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    FOREIGN KEY (wiki_id, book_id) REFERENCES wiki(wiki_id, book_id),
    FOREIGN KEY (wiki_id, base_wiki_version) REFERENCES wiki_version(wiki_id, version)
);
CREATE INDEX ix_wiki_draft_owner ON wiki_draft(member_id, status, updated_at, draft_id);

CREATE TABLE wiki_submission (
    submission_id BINARY(16) PRIMARY KEY,
    draft_id BINARY(16) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    snapshot MEDIUMTEXT NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6),
    CHECK (status IN ('PENDING', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED')),
    CHECK ((status = 'PENDING' AND decided_at IS NULL) OR (status <> 'PENDING' AND decided_at IS NOT NULL)),
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(draft_id) ON DELETE CASCADE
);
CREATE INDEX ix_wiki_submission_status ON wiki_submission(status, submitted_at, submission_id);
