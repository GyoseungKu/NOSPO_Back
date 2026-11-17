package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.UUID;

public interface RecoveryStore {
    WithdrawalArchiveCrypto.Encrypted archive(UUID member,UUID operation,Instant now);
    void complete(UUID operation,Instant now);
}
