package kr.nospo.terms.application;

import kr.nospo.terms.contract.Terms.*;

import java.time.Instant;
import java.util.*;

public interface TermStore {
    void lock();

    List<Version> current(Instant now, boolean lock);

    List<Version> effective(Instant now);

    Optional<Detail> detail(UUID id, Instant now);

    void save(UUID member, Consent consent, Instant now);
}
