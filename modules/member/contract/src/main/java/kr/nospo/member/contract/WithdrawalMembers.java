package kr.nospo.member.contract;

import java.time.Instant;
import java.util.UUID;

/** Called only inside the lifecycle transaction. No personal values may be logged. */
public interface WithdrawalMembers {
    long lock(UUID member);
    byte[] snapshot(UUID member);
    void retain(UUID member, Instant now, Instant deadline);
    void restore(UUID member, byte[] snapshot, Instant now);
}
