package kr.nospo.wiki.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import kr.nospo.support.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static kr.nospo.wiki.application.WikiDrafts.*;
import static kr.nospo.wiki.application.WikiStore.*;
import static kr.nospo.wiki.application.WikiWorkflowStore.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class WikiReviews {
    private final WikiStore store;
    private final WikiWorkflowStore workflow;
    private final WikiDrafts drafts;
    private final WikiSnapshots snapshots;
    private final ObjectMapper json;
    private final ObjectProvider<WikiRewards> rewards;

    public WikiReviews(WikiStore store, WikiWorkflowStore workflow, WikiDrafts drafts, WikiSnapshots snapshots,
                       ObjectMapper json, ObjectProvider<WikiRewards> rewards) {
        this.store = store;
        this.workflow = workflow;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.json = json;
        this.rewards = rewards;
    }

    public record Summary(UUID submissionId, UUID bookId, ReviewStatus status, Instant submittedAt,
                          Instant decidedAt, Integer rewardAmount, UUID parentSubmissionId) {}
    public record Page<T>(List<T> items, String nextCursor, boolean hasNext) {}
    public record Details(ObjectNode data, long revision) {}
    public record Decision(Integer rewardAmount, String rewardReason, String comment, Integer expectedWikiVersion) {}
    public record Revision(Detail draft, boolean created) {}
    public record ReviewSummary(long draftCount, long pending, long changesRequested, long approved, long rejected) {}

    public JsonNode submit(UUID member, UUID draftId, String tag, UUID key) {
        workflow.lockMember(member);
        drafts.find(member, draftId);
        return replay(member, "submit:" + draftId, key, draftId, "", () -> {
            var draft = drafts.editable(member, draftId, tag);
            drafts.scope().resolve(member, draft.bookId(), draft.position());
            var content = drafts.snapshot(draft.snapshot());
            if (!content.path("conflicts").isEmpty()) throw new ApiException(409, "WIKI_VERSION_CONFLICT");
            if (content.path("characters").isEmpty() && content.path("organizations").isEmpty()) throw invalid();
            if (!workflow.submitDraft(draft)) throw mismatch();
            var submission = new Submission(UUID.randomUUID(), draftId, ReviewStatus.PENDING,
                    content.toString(), drafts.now(), null);
            store.insertSubmission(submission);
            activity(draft, submission.id(), draft.parentSubmissionId() == null ? "SUBMITTED" : "RESUBMITTED",
                    member, null, null, null);
            return detail(review(submission.id(), false), false);
        });
    }

    public Page<Summary> list(UUID member, boolean admin, UUID book, ReviewStatus status, String cursor, int limit) {
        if (admin) requireAdmin(member);
        validateLimit(limit);
        var rows = workflow.reviews(admin ? null : member, book, status, decode(cursor), limit + 1);
        return page(rows, limit, WikiReviews::summary, row -> encode(row.submission().submittedAt(), row.submission().id()));
    }

    public Details details(UUID member, UUID id, boolean admin) {
        if (admin) requireAdmin(member);
        var review = review(id, false);
        if (!admin) requireOwner(member, review);
        return new Details(detail(review, admin), review.revision());
    }

    public JsonNode diff(UUID admin, UUID id) {
        requireAdmin(admin);
        var review = review(id, false);
        var draft = review.draft();
        var base = base(draft);
        var proposed = snapshots.publish(drafts.snapshot(review.submission().snapshot()), base, draft.position());
        int current = current(draft.bookId());
        ObjectNode result = json.createObjectNode();
        result.set("baseWikiVersion", json.valueToTree(draft.baseWikiVersion()));
        result.put("currentVersion", current);
        result.put("versionConflict", current != expected(draft));
        result.set("changes", json.valueToTree(snapshots.changes(base == null ? json.createObjectNode() : base, proposed)));
        return result;
    }

    public JsonNode approve(UUID actor, UUID id, String tag, UUID key, Decision input) {
        if (input.rewardAmount() == null || input.rewardAmount() < 0 || input.expectedWikiVersion() == null
                || input.expectedWikiVersion() < 0 || !valid(input.rewardReason(), 1000)
                || (input.comment() != null && !valid(input.comment(), 5000))) throw invalid();
        UUID draftId = lockReviewMembers(actor, id);
        return replay(actor, "approve:" + id, key, draftId, json.writeValueAsString(input), () -> {
            var review = editable(id, tag);
            var draft = review.draft();
            int current = current(draft.bookId());
            if (current != expected(draft) || current != input.expectedWikiVersion())
                throw new ApiException(409, "WIKI_VERSION_CONFLICT");
            int version = current + 1;
            UUID wikiId = current == 0 ? UUID.randomUUID() : draft.wikiId();
            var content = snapshots.publish(drafts.snapshot(review.submission().snapshot()), base(draft), draft.position());
            Instant now = drafts.now();
            try {
                if (current == 0) store.insertWiki(wikiId, draft.bookId());
                store.insertVersion(new PublishedVersion(wikiId, draft.bookId(), version, content.toString(), now));
            } catch (DuplicateKeyException race) {
                throw new ApiException(409, "WIKI_VERSION_CONFLICT");
            }

            UUID reward = null;
            if (input.rewardAmount() > 0) {
                WikiRewards provider = rewards.getIfAvailable();
                if (provider == null) throw new ApiException(503, "WIKI_REWARDS_UNAVAILABLE");
                reward = provider.grant(draft.memberId(), actor, id, input.rewardAmount(), input.rewardReason(), now);
            }
            for (var asset : content.path("assets"))
                workflow.publishAsset(draft.id(), UUID.fromString(asset.path("assetId").asString()));
            workflow.decide(id, ReviewStatus.APPROVED, now, input.comment(), input.rewardAmount(),
                    input.rewardReason(), reward, version);
            activity(draft, id, "APPROVED", actor, input.comment(), input.rewardAmount(), input.rewardReason());
            var result = json.createObjectNode().put("status", "APPROVED").put("publishedWikiVersion", version)
                    .put("rewardAmount", input.rewardAmount());
            result.set("rewardTransactionId", json.valueToTree(reward));
            return result;
        });
    }

    public JsonNode decide(UUID admin, UUID id, String tag, UUID key, ReviewStatus status, String comment) {
        if (!valid(comment, 5000) || (status != ReviewStatus.CHANGES_REQUESTED && status != ReviewStatus.REJECTED))
            throw invalid();
        UUID draftId = lockReviewMembers(admin, id);
        return replay(admin, status.name() + ":" + id, key, draftId, comment, () -> {
            var review = editable(id, tag);
            workflow.decide(id, status, drafts.now(), comment, null, null, null, null);
            activity(review.draft(), id, status.name(), admin, comment, null, null);
            return json.valueToTree(summary(review(id, false)));
        });
    }

    public Revision revision(UUID member, UUID id) {
        workflow.lockMember(member);
        var review = review(id, true);
        requireOwner(member, review);
        if (review.submission().status() != ReviewStatus.CHANGES_REQUESTED)
            throw new ApiException(409, "REVIEW_ALREADY_DECIDED");
        var existingId = workflow.revisionDraft(id);
        if (existingId.isPresent()) {
            Draft existing = drafts.find(member, existingId.get());
            if (existing.status() != DraftStatus.DRAFT) throw new ApiException(409, "DRAFT_NOT_EDITABLE");
            return new Revision(drafts.detail(existing), false);
        }
        var original = review.draft();
        var position = drafts.scope().resolve(member, original.bookId(), original.position());
        var latest = store.latestVersion(original.bookId()).orElse(null);
        var proposed = snapshots.publish(drafts.snapshot(review.submission().snapshot()), base(original), position);
        var currentSnapshot = latest == null ? null : drafts.snapshot(latest.snapshot());
        var before = base(original);
        var conflicts = json.createArrayNode();
        if (latest != null && latest.version() != expected(original))
            proposed = snapshots.rebase(before == null ? drafts.snapshot("{}") : before, proposed, currentSnapshot, conflicts);
        var copied = drafts.copy(proposed.toString(), position);
        if (!conflicts.isEmpty()) {
            var visible = copied.path("baseIds");
            var out = copied.putArray("conflicts");
            for (var conflict : conflicts) {
                String canonicalId = conflict.asString();
                for (var entry : visible.properties()) if (canonicalId.equals(entry.getValue().asString())) {
                    out.add(entry.getKey());
                }

            }
        }
        // Only IDs from the latest public version are canonical for a revised UPDATE draft.
        var canonicalIds = copied.path("baseIds");
        var currentIds = new java.util.HashSet<String>();
        if (currentSnapshot != null) collectIds(currentSnapshot, currentIds);
        var unused = new java.util.ArrayList<String>();
        for (var entry : canonicalIds.properties())
            if (!currentIds.contains(entry.getValue().asString())) unused.add(entry.getKey());
        for (String key : unused) ((ObjectNode) canonicalIds).remove(key);
        var next = new Draft(UUID.randomUUID(), member, original.bookId(), latest == null ? Mode.CREATE : Mode.UPDATE,
                DraftStatus.DRAFT, position, latest == null ? null : latest.wikiId(),
                latest == null ? null : latest.version(), id, 0, copied.toString(), drafts.now());
        store.insertDraft(next);
        copyPrivateAssets(original, next);
        workflow.connectRevisionDraft(id, next.id());
        drafts.activity(next, "CREATED");
        return new Revision(drafts.detail(member, next.id()), true);
    }

    private void copyPrivateAssets(Draft original, Draft next) {
        var content = drafts.snapshot(next.snapshot());
        var replacements = new java.util.HashMap<String, String>();
        for (var row : content.path("assets")) {
            var asset = workflow.asset(UUID.fromString(row.path("assetId").asString())).orElseThrow(WikiDrafts::missing);
            if (asset.draftId() == null) continue;
            if (!original.id().equals(asset.draftId())) throw missing();
            UUID id = UUID.randomUUID();
            workflow.insertAsset(new Asset(id, next.bookId(), next.id(), asset.width(), asset.height(), asset.bytes()));
            replacements.put(asset.id().toString(), id.toString());
            ((ObjectNode) row).put("assetId", id.toString());
        }
        if (replacements.isEmpty()) return;
        for (String kind : List.of("characters", "organizations"))
            for (var entity : content.path(kind))
                for (var profile : entity.path("profiles")) {
                    String replacement = replacements.get(profile.path("imageAssetId").asString());
                    if (replacement != null) ((ObjectNode) profile).put("imageAssetId", replacement);
                }
        store.initializeDraftSnapshot(next.id(), content.toString());
    }

    public Page<JsonNode> activities(UUID member, UUID book, String action, Instant from, Instant to,
                                     String cursor, int limit) {
        validateLimit(limit);
        if (from != null && to != null && from.isAfter(to)) throw invalid();
        if (action != null && !List.of("CREATED", "EDITED", "SUBMITTED", "RESUBMITTED", "APPROVED",
                "CHANGES_REQUESTED", "REJECTED").contains(action)) throw invalid();
        var rows = workflow.activities(member, book, null, action, from, to, decode(cursor), limit + 1);
        return page(rows, limit, a -> {
            var node = json.createObjectNode().put("activityId", a.id().toString()).put("bookId", a.bookId().toString())
                    .put("type", a.action());
            node.set("draftId", json.valueToTree(a.draftId()));
            node.set("submissionId", json.valueToTree(a.submissionId()));
            node.set("occurredAt", json.valueToTree(a.occurredAt()));
            return node;
        }, a -> encode(a.occurredAt(), a.id()));
    }

    public ReviewSummary summary(UUID member) {
        return new ReviewSummary(workflow.countDrafts(member), workflow.countReviews(member, ReviewStatus.PENDING),
                workflow.countReviews(member, ReviewStatus.CHANGES_REQUESTED),
                workflow.countReviews(member, ReviewStatus.APPROVED), workflow.countReviews(member, ReviewStatus.REJECTED));
    }

    public Page<JsonNode> history(UUID admin, UUID id, String cursor, int limit) {
        requireAdmin(admin);
        review(id, false);
        validateLimit(limit);
        var rows = workflow.activities(null, null, id, null, null, null, decode(cursor), limit + 1);
        return page(rows, limit, a -> event(a, true), a -> encode(a.occurredAt(), a.id()));
    }

    private JsonNode replay(UUID actor, String scope, UUID key, UUID draft, String body, Supplier<JsonNode> operation) {
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        var previous = workflow.claim(actor, scope, key, draft, fingerprint, drafts.now());
        if (previous.isPresent()) {
            var recorded = previous.get();
            if (!recorded.fingerprint().equals(fingerprint)) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
            if (recorded.response() == null) throw new ApiException(409, "REQUEST_IN_PROGRESS", 1);
            return json.readTree(recorded.response());
        }
        JsonNode result = operation.get();
        workflow.replay(actor, scope, key, result.toString());
        return result;
    }

    private ObjectNode detail(Review review, boolean admin) {
        ObjectNode node = json.valueToTree(summary(review));
        var snapshot = drafts.snapshot(review.submission().snapshot());
        snapshot.remove("baseIds");
        snapshot.remove("copiedThrough");
        node.set("snapshot", snapshot);
        node.set("baseWikiVersion", json.valueToTree(review.draft().baseWikiVersion()));
        node.set("reviewComment", json.valueToTree(review.comment()));
        node.set("rewardReason", json.valueToTree(review.rewardReason()));
        var history = node.putArray("history");
        var events = workflow.activities(null, null, review.submission().id(), null, null, null, null, 20);
        for (var item : events.reversed()) history.add(event(item, admin));
        return node;
    }

    private JsonNode event(Activity activity, boolean admin) {
        var event = json.createObjectNode().put("action", activity.action());
        event.set("comment", json.valueToTree(activity.comment()));
        event.set("occurredAt", json.valueToTree(activity.occurredAt()));
        if (admin) {
            event.put("historyId", activity.id().toString());
            event.set("actorId", json.valueToTree(activity.actorId()));
            event.set("rewardAmount", json.valueToTree(activity.rewardAmount()));
            event.set("rewardReason", json.valueToTree(activity.rewardReason()));
        }
        return event;
    }

    private void activity(Draft draft, UUID submission, String action, UUID actor, String comment,
                          Integer reward, String reason) {
        workflow.activity(new Activity(UUID.randomUUID(), draft.memberId(), draft.bookId(), draft.id(), submission,
                action, actor, comment, reward, reason, drafts.now()));
    }

    private UUID lockReviewMembers(UUID actor, UUID id) {
        requireAdmin(actor);
        Draft draft = review(id, false).draft();
        UUID owner = draft.memberId();
        if (actor.toString().compareTo(owner.toString()) < 0) {
            workflow.lockMember(actor);
            workflow.lockMember(owner);
        } else {
            workflow.lockMember(owner);
            if (!actor.equals(owner)) workflow.lockMember(actor);
        }
        requireAdmin(actor);
        return draft.id();
    }

    private Review editable(UUID id, String tag) {
        var review = review(id, true);
        if (tag == null) throw new ApiException(428, "PRECONDITION_REQUIRED");
        if (review.submission().status() != ReviewStatus.PENDING) throw new ApiException(409, "REVIEW_ALREADY_DECIDED");
        if (!etag(review.revision()).equals(tag)) throw mismatch();
        return review;
    }

    private int current(UUID book) { return store.latestVersion(book).map(PublishedVersion::version).orElse(0); }

    private static int expected(Draft draft) { return Objects.requireNonNullElse(draft.baseWikiVersion(), 0); }

    private ObjectNode base(Draft draft) {
        if (draft.baseWikiVersion() == null) return null;
        return drafts.snapshot(store.findVersion(draft.bookId(), draft.wikiId(), draft.baseWikiVersion())
                .orElseThrow(WikiDrafts::missing).snapshot());
    }

    private Review review(UUID id, boolean lock) { return workflow.review(id, lock).orElseThrow(WikiDrafts::missing); }

    private static void requireOwner(UUID member, Review review) {
        if (!member.equals(review.draft().memberId())) throw missing();
    }

    private void requireAdmin(UUID member) {
        if (!workflow.administrator(member)) throw new ApiException(403, "FORBIDDEN");
    }

    private static Summary summary(Review review) {
        var info = review.submission();
        return new Summary(info.id(), review.draft().bookId(), info.status(), info.submittedAt(), info.decidedAt(),
                review.rewardAmount(), review.draft().parentSubmissionId());
    }

    static boolean valid(String text, int max) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= max;
    }

    static void validateLimit(int limit) { if (limit < 1 || limit > 100) throw invalid(); }

    static <T, R> Page<R> page(List<T> rows, int limit, Function<T, R> map, Function<T, String> cursor) {
        var items = rows.stream().limit(limit).map(map).toList();
        boolean more = rows.size() > limit;
        return new Page<>(items, more ? cursor.apply(rows.get(limit - 1)) : null, more);
    }

    private static void collectIds(JsonNode node, java.util.Set<String> ids) {
        for (String key : List.of("entityId", "profileId", "entryId", "relationId"))
            if (node.hasNonNull(key)) ids.add(node.path(key).asString());
        if (node.isObject() || node.isArray()) for (var child : node) collectIds(child, ids);
    }
}
