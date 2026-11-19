CREATE TABLE notice_image (
    image_id BINARY(16) PRIMARY KEY,
    png_data MEDIUMBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_notice_image_data CHECK (OCTET_LENGTH(png_data) > 0)
);
