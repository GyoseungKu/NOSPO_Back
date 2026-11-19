package kr.nospo.notice.application;

import java.time.Instant;
import java.util.UUID;

public interface NoticeImageStore {
    void insert(UUID imageId, byte[] png, Instant createdAt);
}
