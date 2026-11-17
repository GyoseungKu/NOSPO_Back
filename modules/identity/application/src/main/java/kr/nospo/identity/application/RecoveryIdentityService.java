package kr.nospo.identity.application;

import kr.nospo.identity.contract.RecoveryIdentity;
import kr.nospo.identity.domain.EmailAddress;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class RecoveryIdentityService implements RecoveryIdentity {
    public record Requested(UUID verificationId, Instant expiresAt, Instant resendAvailableAt) {}
    private final RecoveryEvidenceStore store;
    private final IdentityStore mail;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final TransactionTemplate tx;
    public RecoveryIdentityService(RecoveryEvidenceStore store, IdentityStore mail, Secrets secrets,
            DatabaseGuard guard, Clock clock, PlatformTransactionManager manager) {
        this.store=store; this.mail=mail; this.secrets=secrets; this.guard=guard; this.clock=clock;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Requested request(String input, String ip) {
        String email=EmailAddress.normalize(input);
        Instant now=clock.instant();
        guard.rateLimit("recovery:ip:"+ip,30,Duration.ofHours(1),now);
        guard.rateLimit("recovery:email:"+HexFormat.of().formatHex(secrets.fingerprint(email)),5,Duration.ofHours(1),now);
        return tx.execute(s->{
            UUID challenge=UUID.randomUUID();
            var target=store.target("EMAIL",email);
            if(target.isPresent() && target.get().deadline().isAfter(clock.instant())) {
                store.lock(target.get(),clock.instant());
                enqueueEmail(target.get(),challenge,email);
            }
            // Identical response for unknown, active and expired accounts.
            return new Requested(challenge,clock.instant().plusSeconds(600),clock.instant().plusSeconds(60));
        });
    }
    private void enqueueEmail(RecoveryEvidenceStore.Target target,UUID challenge,String email) {
        Instant now=clock.instant();
        String token=secrets.token();
        store.request(target,challenge,email,secrets.hash(token),now);
        UUID delivery=UUID.randomUUID();
        mail.createDelivery(delivery,challenge,secrets.encrypt((email+"\nRECOVERY:"+token).getBytes(StandardCharsets.UTF_8),"mail:"+delivery),now);
    }
    public Issued confirm(String token) {
        check(token);
        return tx.execute(s->{
            var c=store.challenge(secrets.hash(token)).orElseThrow(()->new ApiException(400,"INVALID_VERIFICATION"));
            store.lock(c.target(),clock.instant());
            c=store.challenge(secrets.hash(token)).orElseThrow(()->new ApiException(400,"INVALID_VERIFICATION"));
            Instant now=clock.instant();
            if(c.invalidatedAt()!=null || !c.expiresAt().isAfter(now)) throw new ApiException(410,"VERIFICATION_EXPIRED");
            if(c.verifiedAt()!=null) throw new ApiException(409,"CHALLENGE_ALREADY_VERIFIED");
            store.verify(c.id(),now);
            return issue(c.target(),"EMAIL",now);
        });
    }
    private Issued issue(RecoveryEvidenceStore.Target target,String method,Instant now) {
        String token=secrets.token();
        Instant expiry=now.plusSeconds(600).isBefore(target.deadline())?now.plusSeconds(600):target.deadline();
        store.proof(target,method,secrets.hash(token),now,expiry);
        return new Issued(token,expiry);
    }
    public Optional<Issued> kakao(String subject,Instant ignored) {
        return store.target("KAKAO",subject).map(t->{store.lock(t,clock.instant());return issue(t,"KAKAO",clock.instant());});
    }
    public Evidence inspect(String token) { check(token); return store.inspect(secrets.hash(token)); }
    public void consume(String token,Evidence evidence,Instant now) { check(token);store.consume(secrets.hash(token),evidence,now); }
    private void check(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(400,"INVALID_PROOF");
    }
}
