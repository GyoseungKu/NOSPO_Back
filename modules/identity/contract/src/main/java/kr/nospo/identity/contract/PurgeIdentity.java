package kr.nospo.identity.contract;

import java.util.UUID;

/** Called only after lifecycle has locked and fenced an expired withdrawal. */
public interface PurgeIdentity {
    void delete(UUID member);
}
