package kr.nospo.identity.contract;

import java.time.Instant;
import java.util.UUID;

/** Identity owns proof validation, reservations and removal of its active personal data. */
public interface WithdrawalIdentity {
    void verify(UUID member, UUID session, byte[] proofHash, Instant now);
    byte[] snapshot(UUID member);
    void retain(UUID operation, UUID member, Instant now);
}
