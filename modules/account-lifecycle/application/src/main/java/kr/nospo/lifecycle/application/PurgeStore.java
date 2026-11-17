package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.*;

public interface PurgeStore {
    record Candidate(UUID member, UUID operation, long generation) {}
    record Lease(Candidate candidate, UUID target, UUID token, int attempts) {}
    List<Candidate> due(Instant now, int limit);
    boolean lockEligible(Candidate candidate, Instant now);
    Optional<Lease> claim(Candidate candidate, Instant now);
    boolean owns(Lease lease, Instant now);
    void deleteRecords(Lease lease);
    void retry(Lease lease, Instant now, Instant next);
}
