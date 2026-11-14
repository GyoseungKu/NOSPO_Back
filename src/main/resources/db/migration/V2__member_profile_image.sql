CREATE TABLE member_profile_image (
    member_id BINARY(16) PRIMARY KEY,
    png_data MEDIUMBLOB NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
