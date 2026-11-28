package kr.nospo.member.contract;

import java.util.Optional;
import java.util.UUID;

public interface MemberAccess {
    record State(String status, String role, long withdrawalGeneration) {
        public boolean active() { return "ACTIVE".equals(status); }
        public boolean administrator() { return active() && "ADMIN".equals(role); }
    }

    Optional<State> state(UUID memberId);

    /** Requires a caller transaction and serializes account-owned writes with withdrawal. */
    Optional<State> lockState(UUID memberId);
}
