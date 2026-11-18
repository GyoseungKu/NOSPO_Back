package kr.nospo.terms.application;

import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
public class ReconsentService implements MemberConsents {
    private final ConsentHistory history;
    private final Members members;
    private final SignupIdentity identity;
    private final TermStore terms;
    private final ConsentStore store;
    private final Clock clock;
    public ReconsentService(ConsentHistory history,Members members,SignupIdentity identity,
            TermStore terms,ConsentStore store,Clock clock) {
        this.history=history;this.members=members;this.identity=identity;
        this.terms=terms;this.store=store;this.clock=clock;
    }
    public Page history(UUID member,String cursor,int limit) { return history.history(member,cursor,limit); }

    private void authenticated(UUID member,UUID session,Instant now) {
        if(member==null || session==null || !identity.sessionActive(member,session,now))
            throw new ApiException(401,"INVALID_CREDENTIALS");
    }

    @Override
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public boolean requiresReconsent(UUID member, UUID session) {
        Instant now=clock.instant();
        authenticated(member,session,now);
        var effective=terms.effective(now);
        var types=new HashSet<String>();
        for(var version:effective) {
            if(!Set.of("SERVICE","PRIVACY","OPTIONAL").contains(version.type())
                    || version.required()=="OPTIONAL".equals(version.type()))
                throw new ApiException(409,"TERMS_VERSION_CHANGED");
            types.add(version.type());
        }
        if(!types.containsAll(Set.of("SERVICE","PRIVACY")))
            throw new ApiException(503,"TERMS_NOT_CONFIGURED");

        var required=effective.stream().filter(Terms.Version::required).toList();
        return !ReconsentPolicy.evaluate(required,store.latestEffectiveChoices(member,now)).pending().isEmpty();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Pending pending(UUID member,UUID session) {
        members.lockActive(member);
        terms.lock();
        Instant now=clock.instant();
        authenticated(member,session,now);
        var state=ReconsentPolicy.evaluate(terms.effective(now),store.events(member));
        return new Pending(state.pending(),state.pending().stream().anyMatch(Terms.Version::required));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<Event> submit(UUID member,UUID session,List<Terms.Consent> consents) {
        if(consents==null || consents.isEmpty() || consents.size()>100)
            throw new ApiException(422,"VALIDATION_ERROR");
        members.lockActive(member);
        terms.lock();
        Instant now=clock.instant();
        authenticated(member,session,now);
        var state=ReconsentPolicy.evaluate(terms.effective(now),store.events(member));
        Map<UUID,Terms.Version> current=new HashMap<>(),pending=new HashMap<>();
        state.current().forEach(v->current.put(v.termVersionId(),v));
        state.pending().forEach(v->pending.put(v.termVersionId(),v));
        Map<UUID,Boolean> input=new HashMap<>();
        for(var choice:consents) {
            if(choice==null || choice.termVersionId()==null || input.put(choice.termVersionId(),choice.agreed())!=null)
                throw new ApiException(422,"VALIDATION_ERROR");
            if(!current.containsKey(choice.termVersionId())) throw new ApiException(409,"TERMS_VERSION_CHANGED");
        }
        for(var required:state.pending()) {
            if(required.required() && !Boolean.TRUE.equals(input.get(required.termVersionId())))
                throw new ApiException(422,"REQUIRED_CONSENT_MISSING");
        }
        // Validate the entire request before the first INSERT. A completed current choice permits
        // exact replay only; changing it belongs to the separate consent-management API.
        for(var choice:consents) {
            UUID version=choice.termVersionId();
            if(current.get(version).required() && !choice.agreed())
                throw new ApiException(422,"REQUIRED_CONSENT_MISSING");
            var previous=state.choices().get(version);
            if(!pending.containsKey(version) && (previous==null || previous.agreed()!=choice.agreed()))
                throw new ApiException(409,"TERMS_VERSION_CHANGED");
        }
        List<Event> result=new ArrayList<>();
        for(var choice:consents) {
            var previous=state.choices().get(choice.termVersionId());
            result.add(previous!=null && previous.agreed()==choice.agreed()
                    ?previous:store.append(member,choice.termVersionId(),choice.agreed(),now));
        }
        authenticated(member,session,clock.instant());
        return List.copyOf(result);
    }
}
