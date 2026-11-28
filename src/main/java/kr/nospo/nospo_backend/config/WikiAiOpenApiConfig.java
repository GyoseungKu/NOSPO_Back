package kr.nospo.nospo_backend.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WikiAiOpenApiConfig {
    private static final String PREFIX = "/api/v1";
    private static final String DRAFT = PREFIX + "/wiki/drafts";
    private static final String REVIEW = PREFIX + "/admin/wiki/submissions";
    private static final String KIND = "/{kind}";
    private static final String ENTITY = KIND + "/{entityId}";
    private static final String PUBLIC_WIKI = PREFIX + "/books/{bookId}/wiki";
    private static final String JSON = "application/json";

    @Bean
    OpenApiCustomizer wikiAiDocumentation() {
        return api -> {
            wikiDocumentation(api);
            var schemas = api.getComponents().getSchemas();
            schemas.get("TermVersionCreate").setRequired(List.of("termId", "type", "version", "title", "content", "required"));
            schemas.get("TermVersionCreate").addProperty("type", new StringSchema()._enum(List.of("SERVICE", "PRIVACY", "OPTIONAL")));
            schemas.get("TermPublication").setRequired(List.of("effectiveAt", "requiresReconsent"));
            api.getPaths().forEach((path, item) -> {
                if (!path.contains("/ai/") && !path.contains("/wiki/")
                        && !path.equals(PUBLIC_WIKI) && !path.equals(DRAFT)
                        && !path.startsWith(PREFIX + "/admin/terms/versions")) return;
                item.readOperationsMap().forEach((method, operation) -> {
                    if (!path.equals(PREFIX + "/ai/credit-policy")) {
                        error(api, operation, "401", "인증이 필요합니다.", "INVALID_CREDENTIALS");
                        if (!path.startsWith(REVIEW) && !path.startsWith(PREFIX + "/admin/"))
                            error(api, operation, "403", "접근 권한 또는 필수 약관 동의를 확인하세요.", "TERMS_RECONSENT_REQUIRED");
                        else error(api, operation, "403", "관리자 권한이 필요합니다.", "FORBIDDEN");
                    }
                    error(api, operation, "400", "요청 형식 또는 커서를 확인하세요.", "INVALID_REQUEST");
                    if (path.startsWith(PUBLIC_WIKI) || path.startsWith(PREFIX + "/ai/questions")
                            || path.startsWith(PREFIX + "/ai/graphs") || path.startsWith(PREFIX + "/ai/jobs"))
                        error(api, operation, "403", "도서의 서재 등록을 확인하세요.", "SHELF_REQUIRED");
                    if (path.contains("{") || path.equals(PREFIX + "/ai/graphs") || path.equals(PREFIX + "/ai/questions"))
                        error(api, operation, "404", "대상을 조회할 수 없습니다.", "RESOURCE_NOT_FOUND");
                    boolean write = method != io.swagger.v3.oas.models.PathItem.HttpMethod.GET;
                    if (write) error(api, operation, "422", "입력값과 현재 진도 범위를 확인하세요.", "VALIDATION_ERROR");
                    if (operation.getParameters() != null) operation.getParameters().forEach(parameter -> {
                        if ("If-Match".equals(parameter.getName())) {
                            parameter.setRequired(true);
                            parameter.setDescription("직전 조회 또는 수정 응답의 ETag를 따옴표까지 그대로 전달합니다.");
                            parameter.setExample("\"1\"");
                            error(api, operation, "412", "현재 버전과 다릅니다.", "VERSION_MISMATCH");
                            error(api, operation, "428", "If-Match 헤더가 필요합니다.", "PRECONDITION_REQUIRED");
                        }
                        if ("kind".equals(parameter.getName()))
                            parameter.schema(new StringSchema()._enum(List.of("characters", "organizations")));
                        if ("limit".equals(parameter.getName()))
                            parameter.schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)
                                    .maximum(java.math.BigDecimal.valueOf(100))._default(20));
                    });
                    if (write && path.startsWith(DRAFT) && !path.equals(DRAFT))
                        error(api, operation, "409", "이미 제출된 초안은 편집할 수 없습니다.", "DRAFT_NOT_EDITABLE");
                    if (write && (path.equals(DRAFT) || path.startsWith(REVIEW) || path.endsWith("/submissions")))
                        error(api, operation, "409", "기준 공개 버전을 확인하세요.", "WIKI_VERSION_CONFLICT");
                    if (write && path.startsWith(REVIEW))
                        error(api, operation, "409", "이미 검수 결정이 끝난 건입니다.", "REVIEW_ALREADY_DECIDED");
                    if (path.endsWith("/assets/{assetId}"))
                        error(api, operation, "409", "프로필에서 이미지 연결을 먼저 해제하세요.", "ASSET_IN_USE");
                    if (write && path.startsWith(PREFIX + "/admin/terms"))
                        error(api, operation, "409", "같은 버전·시행 시각이 있거나 이미 시행된 버전입니다.", "TERM_VERSION_CONFLICT");
                    if (path.equals(PREFIX + "/ai/questions") || path.equals(PREFIX + "/ai/graphs")) {
                        error(api, operation, "409", "예약 가능한 AI 토큰이 부족합니다.", "INSUFFICIENT_CREDITS");
                        error(api, operation, "409", "공개 위키가 준비되지 않았습니다.", "WIKI_NOT_AVAILABLE");
                        error(api, operation, "409", "이미 사용한 요청 키의 본문이 다릅니다.", "IDEMPOTENCY_CONFLICT");
                        error(api, operation, "503", "Book·위키 또는 모델 연결을 확인하세요.", "AI_UNAVAILABLE");
                    }
                    operation.getResponses().forEach((code, response) -> {
                        if (!code.startsWith("2") || response.getContent() == null) return;
                        var wildcard = response.getContent().remove("*/*");
                        if (wildcard != null) response.getContent().addMediaType(JSON, wildcard);
                    });
                    if (write && path.startsWith(PREFIX + "/admin/") && path.contains("/ai/"))
                        error(api, operation, "409", "토큰 잔액 또는 이전 처리 이력을 확인하세요.",
                                path.endsWith("/refund") ? "ALREADY_REFUNDED" : "INSUFFICIENT_AVAILABLE_CREDITS");
                });
            });
            var sse = api.getPaths().get(PREFIX + "/ai/questions/{questionId}/events").getGet();
            sse.getResponses().get("200").getContent().get("text/event-stream").example("""
                    id: 1
                    event: started
                    data: {"questionId":"00000000-0000-4000-8000-000000000001","jobId":"00000000-0000-4000-8000-000000000002","appliedPosition":{"unit":"EPISODE","value":10}}

                    id: 2
                    event: delta
                    data: {"text":"승인된 위키의 내용입니다."}

                    id: 3
                    event: completed
                    data: {"questionId":"00000000-0000-4000-8000-000000000001","chargedCredits":1,"citations":[]}
                    """);
            error(api, sse, "410", "24시간 스트림 재생 기간이 만료됐습니다.", "STREAM_REPLAY_EXPIRED");
        };
    }

    private static void wikiDocumentation(OpenAPI api) {
        var schemas = api.getComponents();
        Schema<?> profile = model(schemas, WikiProfile.class);
        Schema<?> entity = model(schemas, WikiEntity.class);
        Schema<?> entry = model(schemas, WikiEntry.class);
        Schema<?> relation = model(schemas, WikiRelation.class);
        Schema<?> asset = model(schemas, WikiImage.class);
        Schema<?> draft = model(schemas, WikiDraftDetail.class);

        success(api.getPaths().get(DRAFT).getPost(), "201", draft, true);
        var detail = api.getPaths().get(DRAFT + "/{draftId}");
        success(detail.getGet(), "200", draft, true);
        success(detail.getPatch(), "200", model(schemas, WikiDraftSummary.class), true);
        success(detail.getDelete(), "204", null, false);
        var root = DRAFT + "/{draftId}";
        success(api.getPaths().get(root + KIND).getPost(), "201", entity, true);
        var entityPath = api.getPaths().get(root + ENTITY);
        success(entityPath.getGet(), "200", entity, false);
        success(entityPath.getPatch(), "200", entity, true);
        success(entityPath.getDelete(), "204", null, true);
        var newEntity = api.getPaths().get(root + KIND).getPost();
        request(newEntity, new ComposedSchema()
                .addAnyOfItem(model(schemas, WikiCharacterInput.class)).addAnyOfItem(model(schemas, WikiOrganizationInput.class)));

        var profilePath = api.getPaths().get(root + ENTITY + "/profiles");
        var profileItem = api.getPaths().get(root + ENTITY + "/profiles/{profileId}");
        var profileInput = new ComposedSchema().addAnyOfItem(model(schemas, WikiCharacterProfileInput.class))
                .addAnyOfItem(model(schemas, WikiOrganizationProfileInput.class));
        request(profilePath.getPost(), profileInput);
        request(profileItem.getPut(), profileInput);
        editable(api, root + ENTITY + "/profiles", "profileId", profile);
        editable(api, root + ENTITY + "/entries", "entryId", entry);
        editable(api, root + "/relations", "relationId", relation);

        var upload = api.getPaths().get(root + "/assets").getPost();
        success(upload, "201", asset, true);
        upload.getRequestBody().setRequired(true);
        upload.getRequestBody().setContent(new Content().addMediaType("multipart/form-data", new MediaType()
                .schema(new ObjectSchema().addProperty("file", new StringSchema().format("binary")).addRequiredItem("file"))));
        error(api, upload, "413", "이미지 파일은 최대 5 MiB입니다.", "IMAGE_TOO_LARGE");
        success(api.getPaths().get(root + "/assets/{assetId}").getDelete(), "204", null, true);

        Schema<?> submission = model(schemas, WikiSubmissionDetail.class);
        success(api.getPaths().get(root + "/submissions").getPost(), "201", submission, false);
        success(api.getPaths().get(PREFIX + "/wiki/submissions/{submissionId}").getGet(), "200", submission, false);
        var rework = api.getPaths().get(PREFIX + "/wiki/submissions/{submissionId}/revision-drafts").getPost();
        success(rework, "201", draft, true);
        rework.getResponses().addApiResponse("200", response("기존 편집 초안을 반환했습니다.", draft, true));

        var submissionRow = model(schemas, WikiSubmissionSummary.class);
        success(api.getPaths().get(PREFIX + "/me/wiki/submissions").getGet(), "200", page(submissionRow), false);
        success(api.getPaths().get(REVIEW).getGet(), "200", page(submissionRow), false);
        var review = REVIEW + "/{submissionId}";
        success(api.getPaths().get(review).getGet(), "200", submission, true);
        success(api.getPaths().get(review + "/diff").getGet(), "200", model(schemas, WikiReviewDiff.class), false);
        success(api.getPaths().get(review + "/approve").getPost(), "200", model(schemas, WikiApproval.class), false);
        success(api.getPaths().get(review + "/request-changes").getPost(), "200", submissionRow, false);
        success(api.getPaths().get(review + "/reject").getPost(), "200", submissionRow, false);
        success(api.getPaths().get(review + "/history").getGet(), "200", page(model(schemas, WikiReviewEvent.class)), false);
        success(api.getPaths().get(PREFIX + "/me/wiki/activities").getGet(), "200", page(model(schemas, WikiActivity.class)), false);
        success(api.getPaths().get(PUBLIC_WIKI + KIND).getGet(), "200", visiblePage(schemas, WikiVisibleEntity.class), false);
        success(api.getPaths().get(PUBLIC_WIKI + ENTITY).getGet(), "200", model(schemas, WikiVisibleDetail.class), false);
        success(api.getPaths().get(PUBLIC_WIKI + "/relations").getGet(), "200", visiblePage(schemas, WikiVisibleRelation.class), false);

        var positionValue = schemas.getSchemas().get("PositionValue");
        if (positionValue != null) {
            positionValue.addProperty("value", new IntegerSchema().minimum(java.math.BigDecimal.ZERO));
            positionValue.addProperty("unit", new StringSchema()._enum(List.of("PAGE", "EPISODE")));
            positionValue.setRequired(List.of("unit", "value"));
        }
    }

    private static void editable(OpenAPI api, String path, String id, Schema<?> schema) {
        success(api.getPaths().get(path).getPost(), "201", schema, true);
        var item = api.getPaths().get(path + "/{" + id + "}");
        success(item.getPut(), "200", schema, true);
        success(item.getDelete(), "204", null, true);
    }

    private static void request(Operation operation, Schema<?> schema) {
        operation.getRequestBody().setContent(new Content().addMediaType(JSON, new MediaType().schema(schema)));
    }

    private static void success(Operation operation, String code, Schema<?> schema, boolean etag) {
        operation.getResponses().remove("200");
        operation.getResponses().addApiResponse(code, response(switch (code) {
            case "201" -> "생성 완료";
            case "204" -> "처리 완료";
            default -> "조회 또는 처리 완료";
        }, schema, etag));
    }

    private static ApiResponse response(String description, Schema<?> data, boolean etag) {
        var response = new ApiResponse().description(description);
        if (data != null) response.content(new Content().addMediaType(JSON, new MediaType().schema(new ObjectSchema()
                .addProperty("data", data).addProperty("meta", new Schema<>().$ref("#/components/schemas/Meta")))));
        if (etag) response.addHeaderObject("ETag", new Header().description("편집 버전")
                .schema(new StringSchema().example("\"2\"")));
        return response;
    }

    private static void error(OpenAPI api, Operation operation, String status, String description, String code) {
        var response = operation.getResponses().computeIfAbsent(status, ignored -> new ApiResponse()
                .description(description).content(new Content().addMediaType(JSON, new MediaType()
                        .schema(model(api.getComponents(), NospoApiError.class)))));
        if (response.getContent() != null && response.getContent().get(JSON) != null) {
            response.getContent().get(JSON).addExamples(code, new Example().value(Map.of(
                    "error", Map.of("code", code, "message", description, "details", List.of()),
                    "meta", Map.of("requestId", "00000000-0000-4000-8000-000000000001"))));
        }
    }

    private static Schema<?> page(Schema<?> item) {
        return new ObjectSchema().addProperty("items", new io.swagger.v3.oas.models.media.ArraySchema().items(item))
                .addProperty("nextCursor", new StringSchema().nullable(true)).addProperty("hasNext", new Schema<>().type("boolean"));
    }

    private static Schema<?> visiblePage(Components components, Class<?> item) {
        return page(model(components, item)).addProperty("appliedPosition", model(components, WikiPosition.class))
                .addProperty("wikiVersion", new IntegerSchema());
    }

    private static Schema<?> model(Components components, Class<?> type) {
        var name = type.getSimpleName();
        if (components.getSchemas() != null && components.getSchemas().containsKey(name))
            return new Schema<>().$ref("#/components/schemas/" + name);
        var result = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        result.referencedSchemas.forEach(components::addSchemas);
        return result.schema;
    }

    record NospoApiError(ErrorBody error, ErrorMeta meta) {}
    record ErrorBody(String code, String message, List<Map<String, Object>> details) {}
    record ErrorMeta(UUID requestId) {}
    record WikiPosition(String unit, int value) {}
    record WikiCharacterProfileInput(String name, String gender, String personality, String description, String role,
                                     UUID imageAssetId, WikiPosition revealAt) {}
    record WikiOrganizationProfileInput(String name, String description, Void role, UUID imageAssetId, WikiPosition revealAt) {}
    record WikiCharacterInput(WikiPosition firstRevealAt, WikiCharacterProfileInput profile) {}
    record WikiOrganizationInput(WikiPosition firstRevealAt, WikiOrganizationProfileInput profile) {}
    record WikiProfile(UUID profileId, String name, String gender, String personality, String description,
                       String role, UUID imageAssetId, WikiPosition revealAt) {}
    record WikiEntry(UUID entryId, String type, String title, String content, WikiPosition revealAt, WikiPosition sourcePosition) {}
    record WikiEntity(UUID entityId, String kind, WikiPosition firstRevealAt, List<WikiProfile> profiles, List<WikiEntry> entries) {}
    record WikiEndpoint(String type, UUID id) {}
    record WikiEvent(String kind, String type, String description, WikiPosition revealAt, WikiPosition sourcePosition) {}
    record WikiRelation(UUID relationId, WikiEndpoint from, WikiEndpoint to, String type, String description,
                        WikiPosition revealAt, WikiPosition sourcePosition, List<WikiEvent> events) {}
    record WikiImage(UUID assetId, String mediaType, int width, int height) {}
    record WikiDraftSummary(UUID draftId, UUID bookId, String mode, String status, WikiPosition position,
                            Integer baseWikiVersion, UUID parentSubmissionId, long revision, Instant updatedAt) {}
    record WikiDraftDetail(UUID draftId, UUID bookId, String mode, String status, WikiPosition position,
                           Integer baseWikiVersion, UUID parentSubmissionId, long revision, Instant updatedAt,
                           List<WikiEntity> characters, List<WikiEntity> organizations, List<WikiRelation> relations,
                           List<WikiImage> assets, List<UUID> conflicts) {}
    record WikiSubmissionSummary(UUID submissionId, UUID bookId, String status, Instant submittedAt,
                                 Instant decidedAt, Integer rewardAmount, UUID parentSubmissionId) {}
    record WikiSnapshot(List<WikiEntity> characters, List<WikiEntity> organizations,
                        List<WikiRelation> relations, List<WikiImage> assets) {}
    record WikiSubmissionDetail(UUID submissionId, UUID bookId, String status, Instant submittedAt,
                                Instant decidedAt, Integer rewardAmount, UUID parentSubmissionId, Integer baseWikiVersion,
                                WikiSnapshot snapshot, String reviewComment, String rewardReason, List<WikiReviewEvent> history) {}
    record WikiReviewEvent(UUID historyId, String action, UUID actorId, String comment, Integer rewardAmount,
                           String rewardReason, Instant occurredAt) {}
    record WikiApproval(String status, int publishedWikiVersion, int rewardAmount, UUID rewardTransactionId) {}
    record WikiChange(String entityType, UUID entityId, String field, Object before, Object after) {}
    record WikiReviewDiff(Integer baseVersion, int currentVersion, boolean versionConflict, List<WikiChange> changes) {}
    record WikiActivity(UUID activityId, UUID bookId, UUID draftId, UUID submissionId, String type, Instant occurredAt) {}
    record WikiVisibleEntity(UUID entityId, String name, String imageUrl, String description) {}
    record WikiNamedEndpoint(String type, UUID id, String name) {}
    record WikiVisibleRelation(UUID relationId, WikiNamedEndpoint from, WikiNamedEndpoint to, String type, String description) {}
    record WikiVisibleDetail(UUID entityId, String name, String imageUrl, String description, List<WikiProfile> profiles,
                             List<WikiEntry> entries, List<WikiVisibleRelation> relations, List<WikiVisibleRelation> affiliations,
                             List<WikiVisibleRelation> members, WikiPosition appliedPosition, int wikiVersion) {}
}
