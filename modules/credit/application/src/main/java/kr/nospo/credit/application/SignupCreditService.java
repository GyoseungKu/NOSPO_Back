package kr.nospo.credit.application;

import kr.nospo.credit.contract.SignupCredits;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SignupCreditService implements SignupCredits {
    private final CreditStore store;

    public SignupCreditService(CreditStore store) {
        this.store = store;
    }

    @Transactional
    public void grant(UUID member, Instant now) {
        store.createSignupWallet(member, 5, now);
    }
}
