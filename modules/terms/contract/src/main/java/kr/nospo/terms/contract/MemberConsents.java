package kr.nospo.terms.contract;

import java.time.Instant;
import java.util.*;

public interface MemberConsents {
    record Event(UUID consentId, UUID termVersionId, boolean agreed, Instant agreedAt) {}
    record Page(List<Event> items, String nextCursor, boolean hasNext) {}
    record Pending(List<Terms.Version> items, boolean requiredAction) {}
    Page history(UUID member, String cursor, int limit);
    Pending pending(UUID member, UUID session);
    List<Event> submit(UUID member, UUID session, List<Terms.Consent> consents);
}
