package kr.nospo.notice.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import kr.nospo.notice.contract.NoticeImageUpload;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NoticeImageService {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private final Semaphore uploads = new Semaphore(2);
    private final NoticeImageStore store;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public NoticeImageService(
            NoticeImageStore store, Clock clock, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public NoticeImageUpload upload(InputStream file, String filename, String mediaType, long size)
            throws IOException {
        if (size > MAX_FILE_BYTES) throw new ApiException(413, "IMAGE_TOO_LARGE");
        if (size <= 0) throw new ApiException(400, "INVALID_REQUEST");
        if (!uploads.tryAcquire()) throw new ApiException(503, "SERVICE_UNAVAILABLE");
        try {
            byte[] input = file.readNBytes(MAX_FILE_BYTES + 1);
            if (input.length > MAX_FILE_BYTES) throw new ApiException(413, "IMAGE_TOO_LARGE");
            if (input.length != size) throw new ApiException(400, "INVALID_REQUEST");
            byte[] png = NoticeImageCodec.sanitize(input, filename, mediaType);
            UUID imageId = UUID.randomUUID();
            transactions.executeWithoutResult(status -> store.insert(imageId, png, clock.instant()));
            return new NoticeImageUpload(imageId);
        } finally {
            uploads.release();
        }
    }
}
