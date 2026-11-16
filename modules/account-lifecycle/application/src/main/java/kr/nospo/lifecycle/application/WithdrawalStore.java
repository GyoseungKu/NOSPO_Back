package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.*;

public interface WithdrawalStore {
    record Saved(UUID session, byte[] fingerprint) {
        @Override public String toString(){return "WithdrawalSaved[REDACTED]";}
    }
    Optional<Saved> saved(UUID member,UUID key);
    void save(UUID operation,UUID member,long generation,UUID session,UUID key,byte[] fingerprint,
              Instant now,Instant deadline,WithdrawalArchiveCrypto.Encrypted archive);
}
