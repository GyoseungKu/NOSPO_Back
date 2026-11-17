package kr.nospo.terms.contract;

import java.util.UUID;

public interface WithdrawalConsents {
    byte[] snapshot(UUID member);
    void remove(UUID member);
    void restore(UUID member, byte[] snapshot);
}
