package kr.nospo.terms.contract;

import java.time.Instant;
import java.util.*;

public interface Terms {
    record Consent(UUID termVersionId, boolean agreed) {}

    record Version(
            UUID termVersionId,
            UUID termId,
            String type,
            String version,
            String title,
            boolean required,
            Instant effectiveAt,
            boolean requiresReconsent) {}

    record Detail(
            UUID termVersionId,
            UUID termId,
            String type,
            String version,
            String title,
            boolean required,
            Instant effectiveAt,
            boolean requiresReconsent,
            String content) {}

    List<Version> current();

    Detail detail(UUID version);

    void recordSignup(UUID member, List<Consent> consents, Instant now);
}
