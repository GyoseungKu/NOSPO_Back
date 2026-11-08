package kr.nospo.terms.application;

import kr.nospo.terms.contract.Terms.*;

import java.time.Instant;
import java.util.*;

public interface TermStore {
    List<Version> current(Instant now, boolean lock);

    Optional<Detail> detail(UUID id, Instant now);

    void save(UUID member, Consent consent, Instant now);
}
