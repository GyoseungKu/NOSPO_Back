CREATE TABLE book_work (
    work_id BINARY(16) PRIMARY KEY
);

CREATE TABLE book_edition (
    book_id BINARY(16) PRIMARY KEY,
    work_id BINARY(16),
    book_type VARCHAR(16) NOT NULL,
    title VARCHAR(512) NOT NULL,
    publisher VARCHAR(512),
    edition_label VARCHAR(255),
    isbn VARCHAR(32),
    isbn_key VARCHAR(32),
    cover_url VARCHAR(2048),
    description TEXT,
    source VARCHAR(64) NOT NULL,
    source_item_id VARBINARY(768) NOT NULL,
    total_units INT,
    publication_date DATE,
    CONSTRAINT fk_book_edition_work FOREIGN KEY (work_id) REFERENCES book_work(work_id),
    CONSTRAINT uq_book_edition_source UNIQUE (source, source_item_id),
    CONSTRAINT ck_book_edition_type CHECK (book_type IN ('BOOK', 'WEB_NOVEL')),
    CONSTRAINT ck_book_edition_total CHECK (total_units IS NULL OR total_units >= 0)
);
CREATE INDEX ix_book_edition_type_id ON book_edition(book_type, book_id);
CREATE INDEX ix_book_edition_type_isbn ON book_edition(book_type, isbn_key, book_id);

CREATE TABLE book_author (
    book_id BINARY(16) NOT NULL,
    author_order INT NOT NULL,
    author_name VARCHAR(512) NOT NULL,
    PRIMARY KEY (book_id, author_order),
    CONSTRAINT fk_book_author_edition FOREIGN KEY (book_id) REFERENCES book_edition(book_id) ON DELETE CASCADE
);

CREATE TABLE book_episode (
    episode_id BINARY(16) PRIMARY KEY,
    book_id BINARY(16) NOT NULL,
    sequence_no INT NOT NULL,
    episode_label VARCHAR(255) NOT NULL,
    volume_label VARCHAR(255),
    chapter_label VARCHAR(255),
    CONSTRAINT fk_book_episode_edition FOREIGN KEY (book_id) REFERENCES book_edition(book_id) ON DELETE CASCADE,
    CONSTRAINT uq_book_episode_sequence UNIQUE (book_id, sequence_no),
    CONSTRAINT ck_book_episode_sequence CHECK (sequence_no > 0)
);

CREATE TABLE book_search_history (
    history_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    query_text VARCHAR(100) NOT NULL,
    query_key BINARY(32) NOT NULL,
    search_type VARCHAR(8) NOT NULL,
    searched_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_book_search_history_member FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    CONSTRAINT uq_book_search_history_query UNIQUE (member_id, search_type, query_key),
    CONSTRAINT ck_book_search_history_type CHECK (search_type IN ('TITLE', 'AUTHOR', 'ISBN'))
);
CREATE INDEX ix_book_search_history_order ON book_search_history(member_id, searched_at, history_id);
