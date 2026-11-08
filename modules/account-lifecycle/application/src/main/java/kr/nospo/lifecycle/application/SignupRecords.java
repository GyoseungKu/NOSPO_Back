package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.*;

public interface SignupRecords {
    record Saved(byte[] fingerprint, byte[] response) {}

    Optional<Saved> find(String scope, UUID key, Instant now);

    void save(
            UUID member, String scope, UUID key, byte[] fingerprint, byte[] response, Instant now);

    void cleanup(Instant now);
}
