package kr.nospo.credit.contract;

import java.time.Instant;
import java.util.UUID;

public interface SignupCredits {
    void grant(UUID member, Instant now);
}
