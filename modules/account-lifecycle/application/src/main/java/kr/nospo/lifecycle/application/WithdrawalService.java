package kr.nospo.lifecycle.application;

import kr.nospo.identity.contract.WithdrawalIdentity;
import kr.nospo.member.contract.WithdrawalMembers;
import kr.nospo.terms.contract.WithdrawalConsents;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class WithdrawalService {
    private final WithdrawalMembers members;
    private final WithdrawalIdentity identity;
    private final WithdrawalConsents consents;
    private final WithdrawalStore store;
    private final WithdrawalArchiveCrypto crypto;
    private final Secrets secrets;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    public WithdrawalService(WithdrawalMembers members,WithdrawalIdentity identity,WithdrawalConsents consents,
            WithdrawalStore store,WithdrawalArchiveCrypto crypto,Secrets secrets,ObjectMapper mapper,
            Clock clock,PlatformTransactionManager manager){
        this.members=members;this.identity=identity;this.consents=consents;this.store=store;this.crypto=crypto;
        this.secrets=secrets;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void withdraw(UUID member,UUID session,UUID key,String proof){
        if(member==null||session==null)throw new ApiException(401,"INVALID_CREDENTIALS");
        if(key==null||proof==null||!proof.matches("[A-Za-z0-9_-]{43}"))throw new ApiException(422,"VALIDATION_ERROR");
        byte[] fingerprint=secrets.fingerprint("withdrawal:v1:"+session+":"+proof);
        tx.executeWithoutResult(s->{
            long generation=members.lock(member); // One lock serializes retries, withdrawal and member changes.
            var previous=store.saved(member,key);
            if(previous.isPresent()){
                if(!session.equals(previous.get().session())||!MessageDigest.isEqual(fingerprint,previous.get().fingerprint()))
                    throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
                return; // Only an already authenticated in-flight request can reach this after session revocation.
            }
            Instant now=clock.instant(),deadline=now.plus(Duration.ofDays(30));
            identity.verify(member,session,secrets.hash(proof),now);
            UUID operation=UUID.randomUUID();
            Map<String,byte[]> parts=new LinkedHashMap<>();
            byte[] plaintext=null;
            try{
                parts.put("member",members.snapshot(member));
                parts.put("identity",identity.snapshot(member));
                parts.put("consents",consents.snapshot(member));
                plaintext=mapper.writeValueAsBytes(parts);
                var archive=crypto.encrypt(operation,plaintext);
                // Persist the encrypted snapshot before removing any source data, in the SAME transaction.
                store.save(operation,member,generation+1,session,key,fingerprint,now,deadline,archive);
                identity.retain(operation,member,now);
                consents.remove(member);
                members.retain(member,now,deadline);
            }finally{
                if(plaintext!=null)Arrays.fill(plaintext,(byte)0);
                parts.values().forEach(bytes->Arrays.fill(bytes,(byte)0));
            }
        });
    }
}
