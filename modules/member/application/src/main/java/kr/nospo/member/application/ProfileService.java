package kr.nospo.member.application;

import kr.nospo.member.domain.MemberInput;
import kr.nospo.support.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

@Service
public class ProfileService {
    private final ProfileStore store;
    private final ProfileImageCodec codec;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProfileService(
            ProfileStore store,
            ProfileImageCodec codec,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.codec = codec;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
    }

    public String nickname(UUID member, UUID session, String input) {
        String name = MemberInput.nickname(input);
        return tx.execute(
                s -> {
                    store.lockActive(member, session, clock.instant());
                    store.nickname(member, name, MemberInput.nicknameKey(name), clock.instant());
                    return name;
                });
    }

    public String phone(UUID member, UUID session, String input) {
        String phone = MemberInput.phone(input);
        return tx.execute(
                s -> {
                    store.lockActive(member, session, clock.instant());
                    store.phone(member, phone, clock.instant());
                    return phone;
                });
    }

    public String image(UUID member, UUID session, byte[] input) {
        byte[] png = codec.sanitize(input);
        tx.executeWithoutResult(
                s -> {
                    store.lockActive(member, session, clock.instant());
                    store.image(member, png, clock.instant());
                });
        return "/api/v1/me/profile-image";
    }

    public void deleteImage(UUID member, UUID session) {
        tx.executeWithoutResult(
                s -> {
                    store.lockActive(member, session, clock.instant());
                    store.image(member, null, clock.instant());
                });
    }

    public byte[] image(UUID member, UUID session) {
        return tx.execute(
                s -> {
                    store.lockActive(member, session, clock.instant());
                    return store.image(member)
                            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
                });
    }
}
