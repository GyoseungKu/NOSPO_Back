CREATE TABLE notice (
    notice_id BINARY(16) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_notice_title_length CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_notice_summary_length CHECK (CHAR_LENGTH(summary) <= 500),
    CONSTRAINT ck_notice_content_length CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 50000),
    CONSTRAINT ck_notice_pinned CHECK (pinned IN (FALSE, TRUE)),
    CONSTRAINT ck_notice_published CHECK (published IN (FALSE, TRUE)),
    CONSTRAINT ck_notice_published_at CHECK (published = FALSE OR published_at IS NOT NULL)
);

CREATE INDEX ix_notice_public_order
    ON notice (published, pinned DESC, published_at DESC, notice_id DESC);
