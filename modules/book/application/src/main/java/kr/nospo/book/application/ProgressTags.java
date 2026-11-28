package kr.nospo.book.application;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import org.springframework.stereotype.Component;

@Component
public class ProgressTags {
    private static final Pattern FORMAT = Pattern.compile("\"progress-(0|[1-9][0-9]{0,18})-[A-Za-z0-9_-]{43}\"");
    private final Secrets secrets;

    public ProgressTags(Secrets secrets) {
        this.secrets = secrets;
    }

    public String tag(UUID owner, UUID bookId, long revision) {
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secrets.fingerprint("book:progress:v1:" + owner + ":" + bookId + ":" + revision));
        return "\"progress-" + revision + "-" + signature + "\"";
    }

    public String requireSingle(List<String> values) {
        if (values.isEmpty()) throw new ApiException(428, "PRECONDITION_REQUIRED");
        String value = values.getFirst().trim();
        if (values.size() != 1 || !FORMAT.matcher(value).matches())
            throw new ApiException(400, "INVALID_REQUEST");
        return value;
    }

    void check(String supplied, UUID owner, UUID bookId, long revision) {
        if (!MessageDigest.isEqual(tag(owner, bookId, revision).getBytes(US_ASCII), supplied.getBytes(US_ASCII)))
            throw new ApiException(412, "VERSION_MISMATCH");
    }
}
