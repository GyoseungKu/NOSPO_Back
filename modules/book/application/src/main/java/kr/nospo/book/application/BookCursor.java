package kr.nospo.book.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.util.Base64;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import org.springframework.stereotype.Component;

@Component
class BookCursor {
    static final int MAX_STATE_BYTES = 2048;
    private final Secrets secrets;

    BookCursor(Secrets secrets) {
        this.secrets = secrets;
    }

    String encode(String scope, String state) {
        String context = "book:v1:" + scope;
        String payload = base64(secrets.encrypt(state.getBytes(UTF_8), context));
        return payload + "." + base64(secrets.fingerprint(context + ":" + payload));
    }

    String decode(String scope, String cursor) {
        if (cursor == null) return null;
        String context = "book:v1:" + scope;
        try {
            if (cursor.length() > 3000) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(
                    secrets.fingerprint(context + ":" + parts[0]), decodeCanonical(parts[1]))) {
                throw new IllegalArgumentException();
            }
            return new String(secrets.decrypt(decodeCanonical(parts[0]), context), UTF_8);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new ApiException(400, "INVALID_CURSOR");
        }
    }

    private static byte[] decodeCanonical(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!base64(bytes).equals(value)) throw new IllegalArgumentException();
        return bytes;
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
