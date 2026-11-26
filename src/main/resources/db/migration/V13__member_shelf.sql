CREATE TABLE book_progress (
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    current_value INT NOT NULL,
    highest_value INT NOT NULL,
    last_progress_at DATETIME(6),
    revision BIGINT NOT NULL,
    PRIMARY KEY (member_id, book_id),
    CONSTRAINT fk_book_progress_member FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    CONSTRAINT fk_book_progress_edition FOREIGN KEY (book_id) REFERENCES book_edition(book_id),
    CONSTRAINT ck_book_progress_values CHECK (current_value >= 0 AND highest_value >= current_value),
    CONSTRAINT ck_book_progress_revision CHECK (revision >= 0)
);
CREATE INDEX ix_book_progress_activity ON book_progress(member_id, last_progress_at, book_id);

CREATE TABLE book_shelf (
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    added_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id, book_id),
    CONSTRAINT fk_book_shelf_progress FOREIGN KEY (member_id, book_id)
        REFERENCES book_progress(member_id, book_id) ON DELETE CASCADE
);
CREATE INDEX ix_book_shelf_added ON book_shelf(member_id, added_at, book_id);

CREATE TABLE book_reading_record (
    record_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    from_value INT NOT NULL,
    to_value INT NOT NULL,
    added_units INT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_book_record_progress FOREIGN KEY (member_id, book_id)
        REFERENCES book_progress(member_id, book_id) ON DELETE CASCADE,
    CONSTRAINT ck_book_record_values CHECK (from_value >= 0 AND to_value >= 0
        AND added_units >= 0 AND added_units <= to_value
        AND (added_units = 0 OR (to_value > from_value AND added_units <= to_value - from_value)))
);
CREATE INDEX ix_book_record_time ON book_reading_record(member_id, recorded_at, record_id);
CREATE INDEX ix_book_record_edition ON book_reading_record(member_id, book_id, recorded_at, record_id);
