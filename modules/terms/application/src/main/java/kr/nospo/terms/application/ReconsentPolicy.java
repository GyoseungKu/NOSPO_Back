package kr.nospo.terms.application;

import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.*;
import java.time.Instant;
import java.util.*;

/** Pure policy over published, effective versions and internal event order, never display timestamps. */
public final class ReconsentPolicy {
    public record State(List<Terms.Version> current, List<Terms.Version> pending,
                        Map<UUID,MemberConsents.Event> choices) {}
    private ReconsentPolicy() {}

    public static State evaluate(List<Terms.Version> effective, List<ConsentStore.Ordered> events) {
        Map<UUID,ConsentStore.Ordered> last=new HashMap<>();
        for(var row:events) last.merge(row.event().termVersionId(),row,
                (a,b)->a.order()>b.order()?a:b);
        Map<UUID,MemberConsents.Event> choices=new HashMap<>();
        last.forEach((id,row)->choices.put(id,row.event()));
        Map<UUID,List<Terms.Version>> groups=new HashMap<>();
        for(var version:effective) groups.computeIfAbsent(version.termId(),id->new ArrayList<>()).add(version);
        List<Terms.Version> current=new ArrayList<>(),pending=new ArrayList<>();
        for(var versions:groups.values()) {
            versions.sort(Comparator.comparing(Terms.Version::effectiveAt));
            var latest=versions.get(versions.size()-1);
            current.add(latest);
            var decision=choices.get(latest.termVersionId());
            if(decision!=null && decision.agreed()) continue;
            // Defer undefined kind scopes instead of inventing an obligation or clearing one.
            if(!Set.of("SERVICE","PRIVACY","OPTIONAL").contains(latest.type())
                    || latest.required()=="OPTIONAL".equals(latest.type()))
                throw new ApiException(409,"TERMS_VERSION_CHANGED");
            // Required/optional transitions need a separate policy. Never silently clear an obligation.
            if(versions.stream().anyMatch(v->v.required()!=latest.required()))
                throw new ApiException(409,"TERMS_VERSION_CHANGED");
            // Explicit optional false completes a choice. Recovery appends its own decision.
            if(decision!=null && !latest.required()) continue;
            if(!latest.required()) {
                if(latest.requiresReconsent() && decision==null) pending.add(latest);
                continue;
            }
            Instant covered=null;
            for(var version:versions) {
                var choice=choices.get(version.termVersionId());
                if(choice!=null && choice.agreed() && (covered==null || version.effectiveAt().isAfter(covered)))
                    covered=version.effectiveAt();
            }
            if(covered==null) {
                pending.add(latest);continue;
            }
            for(var version:versions) {
                if(version.required() && version.requiresReconsent()
                        && (covered==null || version.effectiveAt().isAfter(covered))) {
                    pending.add(latest);break;
                }
            }
        }
        Comparator<Terms.Version> order=Comparator.comparing(Terms.Version::type);
        current.sort(order);pending.sort(order);
        return new State(List.copyOf(current),List.copyOf(pending),Map.copyOf(choices));
    }
}
