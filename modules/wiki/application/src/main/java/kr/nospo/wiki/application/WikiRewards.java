package kr.nospo.wiki.application;

import java.time.Instant;
import java.util.UUID;

public interface WikiRewards {
    UUID grant(UUID member, UUID actor, UUID submission, int amount, String reason, Instant now);
}
