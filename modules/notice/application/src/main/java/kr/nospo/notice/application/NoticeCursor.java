package kr.nospo.notice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import kr.nospo.notice.application.NoticeStore.Position;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import org.springframework.stereotype.Component;

@Component
class NoticeCursor {
    private static final String DOMAIN = "public-notices:v1:";
    private final Secrets secrets;

    NoticeCursor(Secrets secrets) {
        this.secrets = secrets;
    }

    record PagePosition(Instant asOf, Position last) {}

    String encode(PagePosition position, NoticeSort sort) {
        Position last = position.last();
        String body = position.asOf() + "|" + (last.pinned() ? "1" : "0")
                + "|" + last.publishedAt() + "|" + last.noticeId();
        return base64(body.getBytes(StandardCharsets.UTF_8)) + "." + base64(secrets.fingerprint(domain(sort) + body));
    }

    PagePosition decode(String cursor, NoticeSort sort) {
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = decodeCanonical(parts[0]);
            byte[] signature = decodeCanonical(parts[1]);
            String body = new String(payload, StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(secrets.fingerprint(domain(sort) + body), signature)) {
                throw new IllegalArgumentException();
            }
            String[] fields = body.split("\\|", -1);
            if (fields.length != 4 || !("0".equals(fields[1]) || "1".equals(fields[1]))) {
                throw new IllegalArgumentException();
            }
            Instant asOf = Instant.parse(fields[0]);
            Instant publishedAt = Instant.parse(fields[2]);
            if (publishedAt.isAfter(asOf)) throw new IllegalArgumentException();
            return new PagePosition(asOf,
                    new Position("1".equals(fields[1]), publishedAt, UUID.fromString(fields[3])));
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
