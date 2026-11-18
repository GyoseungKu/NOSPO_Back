package kr.nospo.terms.application;

import kr.nospo.terms.contract.MemberConsents.Event;
import java.time.Instant;
import java.util.*;

public interface ConsentStore {
    record Ordered(Event event, long order) {}
    record Position(long ceiling, Instant agreedAt, UUID consentId) {}
    List<Ordered> events(UUID member);
    List<Ordered> latestEffectiveChoices(UUID member, Instant now);
    long ceiling(UUID member);
    List<Event> history(UUID member, Position position, int count);
    Event append(UUID member, UUID version, boolean agreed, Instant now);
}
