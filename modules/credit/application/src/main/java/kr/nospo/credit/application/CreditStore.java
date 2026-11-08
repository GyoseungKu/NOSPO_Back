package kr.nospo.credit.application;

import java.time.Instant;
import java.util.UUID;

public interface CreditStore {
    void createSignupWallet(UUID member, int amount, Instant now);
}
