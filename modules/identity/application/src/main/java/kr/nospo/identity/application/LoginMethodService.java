package kr.nospo.identity.application;

import kr.nospo.identity.contract.LoginMethods;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Service
public class LoginMethodService implements LoginMethods {
    private final LoginMethodStore store;
    private final Clock clock;

    public LoginMethodService(LoginMethodStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Status get(UUID member) {
        var methods = store.methods(member)
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS"));
        var names = new ArrayList<String>();
        if (methods.email()) names.add("EMAIL");
        if (methods.kakao()) names.add("KAKAO");
        return new Status(names, methods.kakao() && !methods.email(), !methods.kakao());
    }

    // Internal persistence operation only. The future OAuth application flow must validate
    // and consume the member/session-bound link proof in the same transaction before calling.
    // Deliberately not exposed as an HTTP endpoint or a cross-module contract.
    @Transactional
    public void recordVerifiedKakao(UUID member, String subject) {
        if (subject == null || !subject.matches("[0-9]{1,128}"))
            throw new ApiException(422, "VALIDATION_ERROR");
        store.lockActiveMember(member);
        store.insertKakao(member, subject, clock.instant());
    }
}
