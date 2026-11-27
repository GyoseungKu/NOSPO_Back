package kr.nospo.wiki.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.wiki.application.WikiStore.*;

public interface WikiWorkflowStore {
    record Review(Submission submission, Draft draft, long revision, String comment, Integer rewardAmount,
                  String rewardReason, UUID rewardTransactionId, Integer publishedVersion) {}
    record Activity(UUID id, UUID memberId, UUID bookId, UUID draftId, UUID submissionId, String action,
                    UUID actorId, String comment, Integer rewardAmount, String rewardReason, Instant occurredAt) {}
    record Replay(String fingerprint, String response) {}
    record Asset(UUID id, UUID bookId, UUID draftId, int width, int height, byte[] bytes) {}

    Optional<Review> review(UUID id, boolean lock);
    List<Review> reviews(UUID member, UUID book, ReviewStatus status, DraftCursor cursor, int limit);
    boolean submitDraft(Draft draft);
    void decide(UUID id, ReviewStatus status, Instant now, String comment, Integer amount,
                String reason, UUID rewardTransaction, Integer version);
    void activity(Activity activity);
    List<Activity> activities(UUID member, UUID book, UUID submission, String action, Instant from, Instant to,
                              DraftCursor cursor, int limit);
    Optional<Replay> claim(UUID actor, String scope, UUID key, UUID draft, String fingerprint, Instant now);
    void replay(UUID actor, String scope, UUID key, String response);
    Optional<UUID> revisionDraft(UUID submission);
    void connectRevisionDraft(UUID submission, UUID draft);
    void lockMember(UUID member);
    boolean administrator(UUID member);
    long countDrafts(UUID member);
    long countReviews(UUID member, ReviewStatus status);

    void insertAsset(Asset asset);
    Optional<Asset> asset(UUID id);
    List<String> ownedSnapshots(UUID member, UUID book);
    void removeAsset(UUID draft, UUID id);
    void publishAsset(UUID draft, UUID id);
}
