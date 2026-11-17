package kr.nospo.member.contract;

import java.util.UUID;

public interface PurgeMembers {
    /** Final deletion also cascades the approved wallet and signup ledger deletion. */
    void delete(UUID member, long generation);
}
