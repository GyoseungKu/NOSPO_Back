package kr.nospo.wiki.application;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import kr.nospo.wiki.application.WikiBookAccess.Position;

public interface WikiStore {
    enum Mode { CREATE, UPDATE }
    enum DraftStatus { DRAFT, SUBMITTED }
    enum ReviewStatus { PENDING, APPROVED, CHANGES_REQUESTED, REJECTED }

    record Draft(UUID id, UUID memberId, UUID bookId, Mode mode, DraftStatus status, Position position,
                 UUID wikiId, Integer baseWikiVersion, UUID parentSubmissionId, long revision,
                 String snapshot, Instant updatedAt) {}

    record Submission(UUID id, UUID draftId, ReviewStatus status, String snapshot,
                      Instant submittedAt, Instant decidedAt) {}

    record PublishedVersion(UUID wikiId, UUID bookId, int version, String snapshot, Instant publishedAt) {}

    void insertDraft(Draft draft);
    void initializeDraftSnapshot(UUID draft, String snapshot);
    Optional<Draft> findDraft(UUID memberId, UUID draftId);

    record DraftCursor(Instant updatedAt, UUID id) {}
    record DraftSummary(UUID id, UUID bookId, Mode mode, DraftStatus status, Position position,
                        Integer baseWikiVersion, UUID parentSubmissionId, long revision, Instant updatedAt) {}
    List<DraftSummary> drafts(UUID memberId, UUID bookId, DraftCursor after, int limit);
    boolean saveDraft(Draft draft, long expectedRevision);
    boolean deleteDraft(UUID memberId, UUID draftId, long expectedRevision);
    Optional<PublishedVersion> findVersion(UUID bookId, UUID wikiId, int version);

    void insertSubmission(Submission submission);
    Optional<Submission> findSubmission(UUID submissionId);

    void insertWiki(UUID wikiId, UUID bookId);
    void insertVersion(PublishedVersion version);
    Optional<PublishedVersion> latestVersion(UUID bookId);
}
