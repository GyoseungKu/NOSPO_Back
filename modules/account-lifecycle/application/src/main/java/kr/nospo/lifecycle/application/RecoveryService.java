package kr.nospo.lifecycle.application;

import kr.nospo.identity.contract.*;
import kr.nospo.member.contract.WithdrawalMembers;
import kr.nospo.terms.contract.*;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@Service
public class RecoveryService {
    public record Command(String recoveryProof,String newPassword,List<Terms.Consent> consents) {
        @Override public String toString() {return "RecoveryCommand[REDACTED]";}
    }
    private final RecoveryIdentity evidence;
    private final SignupIdentity sessions;
    private final WithdrawalIdentity identity;
    private final WithdrawalMembers members;
    private final WithdrawalConsents history;
    private final Terms terms;
    private final RecoveryStore store;
    private final WithdrawalArchiveCrypto crypto;
    private final ObjectMapper mapper;
    private final SignupService profiles;
    private final Clock clock;
    private final TransactionTemplate tx;
    public RecoveryService(RecoveryIdentity evidence,SignupIdentity sessions,WithdrawalIdentity identity,
            WithdrawalMembers members,WithdrawalConsents history,Terms terms,RecoveryStore store,
            WithdrawalArchiveCrypto crypto,ObjectMapper mapper,SignupService profiles,Clock clock,PlatformTransactionManager manager) {
        this.evidence=evidence;this.sessions=sessions;this.identity=identity;this.members=members;this.history=history;
        this.terms=terms;this.store=store;this.crypto=crypto;this.mapper=mapper;this.profiles=profiles;this.clock=clock;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SignupService.Completed recover(Command command) {
        if(command==null || command.consents()==null || command.consents().size()>100) throw new ApiException(422,"VALIDATION_ERROR");
        var proof=evidence.inspect(command.recoveryProof());
        // Expensive password hashing is outside locks. Kakao recovery keeps the existing login methods.
        String password="EMAIL".equals(proof.method())?sessions.passwordHash(command.newPassword()):null;
        if("KAKAO".equals(proof.method()) && command.newPassword()!=null) throw new ApiException(422,"VALIDATION_ERROR");
        return tx.execute(s->{
            members.lock(proof.member());
            Instant now=clock.instant();
            var archive=store.archive(proof.member(),proof.operation(),now);
            evidence.consume(command.recoveryProof(),proof,now);
            byte[] plaintext=crypto.decrypt(proof.operation(),archive);
            Map<String,byte[]> parts=new HashMap<>();
            try {
                var root=mapper.readTree(plaintext);
                for(String name:List.of("member","identity","consents")) parts.put(name,Base64.getDecoder().decode(root.path(name).asString()));
                members.restore(proof.member(),parts.get("member"),now);
                identity.restore(proof.member(),parts.get("identity"),password,now);
                history.restore(proof.member(),parts.get("consents"));
                terms.recordSignup(proof.member(),command.consents(),now);
                // Recheck after archive processing / term locks. Exactly the deadline is excluded.
                store.complete(proof.operation(),clock.instant());
                var tokens=sessions.openSession(proof.member(),clock.instant());
                var result=new SignupResult(tokens.accessToken(),"Bearer",900,profiles.me(proof.member()));
                return new SignupService.Completed(result,tokens.refreshToken(),tokens.refreshExpiresAt());
            } finally {
                Arrays.fill(plaintext,(byte)0);parts.values().forEach(bytes->Arrays.fill(bytes,(byte)0));
            }
        });
    }
}
