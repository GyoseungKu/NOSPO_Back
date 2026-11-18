package kr.nospo.identity.application;

import java.util.UUID;

/** Checks application access before issuing a proof for an existing Kakao link flow. */
public interface KakaoLinkAccess {
    void check(UUID member, UUID session);
}
