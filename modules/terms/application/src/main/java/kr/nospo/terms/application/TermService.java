package kr.nospo.terms.application;

import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.Terms;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TermService implements Terms {
    private final TermStore store;
    private final Clock clock;

    public TermService(TermStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public List<Version> current() {
        return store.current(clock.instant(), false);
    }

    public Detail detail(UUID id) {
        return store.detail(id, clock.instant())
                .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }

    @Transactional
    public void recordSignup(UUID member, List<Consent> consents, Instant now) {
        var versions = store.current(now, true);
        if (versions.stream().noneMatch(v -> v.type().equals("SERVICE") && v.required())
                || versions.stream().noneMatch(v -> v.type().equals("PRIVACY") && v.required()))
            throw new ApiException(503, "TERMS_NOT_CONFIGURED");
        var inputs = new HashMap<UUID, Boolean>();
        for (var consent : consents)
            if (consent == null
                    || consent.termVersionId() == null
                    || inputs.put(consent.termVersionId(), consent.agreed()) != null)
                throw new ApiException(422, "VALIDATION_ERROR");
        var currentIds =
                versions.stream()
                        .map(Version::termVersionId)
                        .collect(java.util.stream.Collectors.toSet());
        if (!currentIds.containsAll(inputs.keySet()))
            throw new ApiException(409, "TERMS_VERSION_CHANGED");
        if (versions.stream().anyMatch(v -> v.required() && !inputs.containsKey(v.termVersionId())))
            throw new ApiException(422, "REQUIRED_CONSENT_MISSING");
        if (!currentIds.equals(inputs.keySet())) throw new ApiException(422, "VALIDATION_ERROR");
        if (versions.stream().anyMatch(v -> v.required() && !inputs.get(v.termVersionId())))
            throw new ApiException(422, "REQUIRED_CONSENT_MISSING");
        consents.forEach(c -> store.save(member, c, now));
    }
}
