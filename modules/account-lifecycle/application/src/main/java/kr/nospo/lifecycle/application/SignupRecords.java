package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.*;

public interface SignupRecords {
    record Saved(byte[] fingerprint, byte[] response) {}

    default Optional<Saved> find(String scope, UUID key, Instant now) {
        return find(scope, "/api/v1/auth/signup", key, now);
    }

    Optional<Saved> find(String scope, String path, UUID key, Instant now);

    default void save(UUID member, String scope, UUID key, byte[] fingerprint, byte[] response, Instant now) {
        save(member, scope, "/api/v1/auth/signup", key, fingerprint, response, now);
    }

    void save(UUID member, String scope, String path, UUID key, byte[] fingerprint, byte[] response, Instant now);

    void cleanup(Instant now);
}
