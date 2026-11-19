package kr.nospo.notice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import kr.nospo.notice.application.NoticeStore.AdminPosition;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import org.springframework.stereotype.Component;

@Component
class AdminNoticeCursor {
    private static final String DOMAIN = "admin-notices:v1:";
    private final Secrets secrets;

    AdminNoticeCursor(Secrets secrets) {
        this.secrets = secrets;
    }

    String encode(AdminPosition position, NoticeSort sort) {
        String body = position.createdAt() + "|" + position.noticeId();
        return base64(body.getBytes(StandardCharsets.UTF_8)) + "." + base64(secrets.fingerprint(domain(sort) + body));
    }

    AdminPosition decode(String cursor, NoticeSort sort) {
        try {
            if (cursor.length() > 192) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            String body = new String(decodeCanonical(parts[0]), StandardCharsets.UTF_8);
            byte[] signature = decodeCanonical(parts[1]);
            if (!MessageDigest.isEqual(secrets.fingerprint(domain(sort) + body), signature)) {
                throw new IllegalArgumentException();
            }
            String[] fields = body.split("\\|", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            return new AdminPosition(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw new ApiException(400, "INVALID_CURSOR");
        }
    }

    private static String domain(NoticeSort sort) {
        return sort == NoticeSort.OLDEST ? DOMAIN + "oldest:" : DOMAIN;
    }

    private static byte[] decodeCanonical(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!base64(bytes).equals(value)) throw new IllegalArgumentException();
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
