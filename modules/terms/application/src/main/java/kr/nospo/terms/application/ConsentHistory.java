package kr.nospo.terms.application;

import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.MemberConsents;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class ConsentHistory {
    private final Members members;
    private final ConsentStore store;
    private final ConsentCursor cursors;
    public ConsentHistory(Members members,ConsentStore store,ConsentCursor cursors) {
        this.members=members;this.store=store;this.cursors=cursors;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public MemberConsents.Page history(UUID member,String cursor,int limit) {
        if(limit<1 || limit>100) throw new ApiException(422,"VALIDATION_ERROR");
        var position=cursor==null?null:cursors.decode(member,cursor);
        members.lockActive(member);
        if(position==null) position=new ConsentStore.Position(store.ceiling(member),null,null);
        var rows=store.history(member,position,limit+1);
        boolean more=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        String next=null;
        if(more) {
            var last=items.get(items.size()-1);
            next=cursors.encode(member,new ConsentStore.Position(position.ceiling(),last.agreedAt(),last.consentId()));
        }
        return new MemberConsents.Page(items,next,more);
    }
}
