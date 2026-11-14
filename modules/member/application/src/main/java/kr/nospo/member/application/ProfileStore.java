package kr.nospo.member.application;

import java.time.Instant;
import java.util.*;

public interface ProfileStore {
    void lockActive(UUID member, UUID session, Instant now);

    void nickname(UUID member, String nickname, String key, Instant now);

    void phone(UUID member, String phone, Instant now);

    void image(UUID member, byte[] png, Instant now);

    Optional<byte[]> image(UUID member);
}
