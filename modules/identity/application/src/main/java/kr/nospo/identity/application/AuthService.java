package kr.nospo.identity.application;

import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.identity.domain.EmailAddress;
import kr.nospo.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class AuthService {
    public record Session(
            String accessToken,
            String tokenType,
            int expiresIn,
            String refresh,
            Instant expiry,
            UUID member) {}

    private final AccountStore store;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final SignupIdentity identity;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String dummy;

    public AuthService(
            AccountStore store,
            Secrets secrets,
            DatabaseGuard guard,
            SignupIdentity identity,
            IdentityCrypto crypto,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.secrets = secrets;
        this.guard = guard;
        this.identity = identity;
        this.crypto = crypto;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        dummy = crypto.hashPassword(UUID.randomUUID().toString());
    }

    public Session login(String input, String password, String ip) {
        String email = EmailAddress.normalize(input);
        Instant now = clock.instant();
        guard.rateLimit("login:ip:" + ip, 30, Duration.ofMinutes(15), now);
        guard.rateLimit("login:email:" + email, 10, Duration.ofMinutes(15), now);
        var account = store.credential(email);
        String hash = account.map(AccountStore.Credential::hash).orElse(dummy);
        if (!crypto.matchesPassword(password, hash) || account.isEmpty())
            throw new ApiException(401, "INVALID_CREDENTIALS");
        UUID member = account.get().member();
        return tx.execute(
                s -> {
                    if (!hash.equals(store.lockPassword(member)))
                        throw new ApiException(401, "INVALID_CREDENTIALS");
                    var tokens = identity.openSession(member, clock.instant());
                    return new Session(
                            tokens.accessToken(),
                            "Bearer",
                            900,
                            tokens.refreshToken(),
                            tokens.refreshExpiresAt(),
                            member);
                });
    }

    public Session refresh(String token) {
        check(token);
        Session result =
                tx.execute(
                        s -> {
                            var found = store.lockRefresh(secrets.hash(token));
                            if (found.isEmpty()) return null;
                            var old = found.get();
                            Instant now = clock.instant();
                            if (old.used() != null) {
                                store.revoke(old.session(), now);
                                return null;
                            }
                            if (old.revoked() != null
                                    || old.tokenRevoked() != null
                                    || !old.tokenExpiry().isAfter(now)
                                    || !identity.sessionActive(old.member(), old.session(), now))
                                return null;
                            String fresh = secrets.token();
                            store.rotate(old, UUID.randomUUID(), secrets.hash(fresh), now);
                            return new Session(
                                    crypto.accessToken(old.member(), old.session(), now),
                                    "Bearer",
                                    900,
                                    fresh,
                                    old.expiry(),
                                    old.member());
                        });
        if (result == null) throw new ApiException(401, "INVALID_CREDENTIALS");
        return result;
    }

    public void logout(UUID sid, String token) {
        tx.executeWithoutResult(
                s -> {
                    if (sid != null) store.revoke(sid, clock.instant());
                    else {
                        check(token);
                        store.revoke(
                                store.session(secrets.hash(token))
                                        .orElseThrow(
                                                () -> new ApiException(401, "INVALID_CREDENTIALS")),
                                clock.instant());
                    }
                });
    }

    private void check(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }
}
