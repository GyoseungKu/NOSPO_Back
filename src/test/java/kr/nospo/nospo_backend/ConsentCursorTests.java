package kr.nospo.nospo_backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import kr.nospo.member.contract.Members;
import kr.nospo.support.*;
import kr.nospo.terms.application.*;
import kr.nospo.terms.contract.MemberConsents;
import org.junit.jupiter.api.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

class ConsentCursorTests {
    Secrets secrets;
    ConsentCursor cursor;
    final UUID member=UUID.randomUUID();
    final ConsentStore.Position position=new ConsentStore.Position(5,Instant.parse("2026-11-17T00:00:00.123456Z"),UUID.randomUUID());
    @BeforeEach void setup() {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String key=Base64.getEncoder().encodeToString(bytes);
        secrets=new Secrets(key,key);cursor=new ConsentCursor(secrets);
    }
    void invalid(Runnable task) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(ApiException.class,e->{
            assertThat(e.status()).isEqualTo(400);assertThat(e.code()).isEqualTo("INVALID_CURSOR");
        });
    }
    @Test void signedCursorRoundTripsWithoutLosingMicroseconds() {
        assertThat(cursor.decode(member,cursor.encode(member,position))).isEqualTo(position);
    }
    @Test void cursorCannotBeTransferredToAnotherMember() {
        invalid(()->cursor.decode(UUID.randomUUID(),cursor.encode(member,position)));
    }
    @Test void alteredSignatureAndMalformedCursorsAreRejected() {
        String valid=cursor.encode(member,position);
        invalid(()->cursor.decode(member,valid.substring(0,valid.indexOf('.')+1)+"AAAA"));
        for(String value:List.of("","broken","A.A",".","A".repeat(769),valid+"="))
            invalid(()->cursor.decode(member,value));
    }
    @Test void semanticValidationRejectsEvenCorrectlySignedInvalidPositions() {
        var bad=new ConsentStore.Position(0,position.agreedAt(),position.consentId());
        invalid(()->cursor.decode(member,cursor.encode(member,bad)));
    }
    @Test void historyPaginationUsesCeilingAndOnlyReturnsACursorWhenMoreRowsExist() {
        Members members=mock(Members.class);ConsentStore store=mock(ConsentStore.class);
        var service=new ConsentHistory(members,store,cursor);
        var first=new MemberConsents.Event(UUID.randomUUID(),UUID.randomUUID(),true,position.agreedAt());
        var second=new MemberConsents.Event(UUID.randomUUID(),UUID.randomUUID(),false,position.agreedAt());
        when(store.ceiling(member)).thenReturn(7L);
        when(store.history(member,new ConsentStore.Position(7,null,null),2)).thenReturn(List.of(first,second));
        var page=service.history(member,null,1);
        assertThat(page.items()).containsExactly(first);assertThat(page.hasNext()).isTrue();
        var next=cursor.decode(member,page.nextCursor());
        assertThat(next).isEqualTo(new ConsentStore.Position(7,first.agreedAt(),first.consentId()));
        when(store.history(member,next,2)).thenReturn(List.of(second));
        var last=service.history(member,page.nextCursor(),1);
        assertThat(last.items()).containsExactly(second);assertThat(last.hasNext()).isFalse();assertThat(last.nextCursor()).isNull();
        var order=inOrder(members,store);order.verify(members).lockActive(member);order.verify(store).ceiling(member);
    }
    @Test void invalidLimitsAndCursorsDoNotReadHistory() {
        Members members=mock(Members.class);ConsentStore store=mock(ConsentStore.class);
        var service=new ConsentHistory(members,store,cursor);
        for(int limit:List.of(0,101))
            assertThatThrownBy(()->service.history(member,null,limit)).isInstanceOf(ApiException.class);
        invalid(()->service.history(member,"bad",20));
        verifyNoInteractions(members,store);
    }
}
