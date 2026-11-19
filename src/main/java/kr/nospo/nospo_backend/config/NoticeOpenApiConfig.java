package kr.nospo.nospo_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NoticeOpenApiConfig {
    private static final String PUBLIC = "/api/v1/notices";
    private static final String ADMIN = "/api/v1/admin/notices";
    private static final String ID_PATH = "/{noticeId}";
    private static final String JSON = "application/json";
    private static final String NOTICE_ID = "10000000-0000-0000-0000-000000000001";
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TIME = "2026-11-19T12:00:00Z";
    private static final String ADMIN_ACCESS = """
            유효한 Bearer JWT와 활성 세션, ACTIVE 회원 상태, 현재 DB의 ADMIN 역할 및 필수 약관 재동의가 필요합니다.
            Swagger UI의 Authorize에는 액세스 토큰 값을 입력하세요.
            """;
    private static final String WRITE_ACCESS = ADMIN_ACCESS + """
            먼저 같은 브라우저에서 관리자 공지 목록 GET을 호출해 CSRF 세션 쿠키를 유지하고 응답의
            X-CSRF-TOKEN을 복사하세요. Try it out에서 이 토큰을 요청 헤더 입력란에 넣으세요.
            브라우저가 세션 쿠키를 전송해야 하며, 프론트 요청은 credentials: include를 사용하세요.
            CSRF 쿠키만으로 인증할 수 없습니다. 허용 Origin과 브라우저 쿠키 정책을 확인하세요.
            """;
    private static final String CURSOR = """
            첫 페이지에는 cursor를 생략하고, 다음 페이지에는 받은 nextCursor를 그대로 전달하세요.
            커서는 HMAC 서명된 불투명한 값입니다. 수정하거나 다른 정렬·목록의 커서와 섞어 쓰면 거부됩니다.
            정렬을 바꿀 때는 cursor를 지우고 첫 페이지부터 조회하세요.
            빈 목록과 마지막 페이지는 nextCursor가 null이고 hasNext가 false입니다.
            """;
    private static final String MARKDOWN = """
            content는 서버에서 HTML로 변환하지 않은 Markdown 원문입니다.
            프론트에서 렌더링할 때 원시 HTML과 위험한 링크를 차단하세요.
            """;
    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String NOT_FOUND = "RESOURCE_NOT_FOUND";

    @Bean
    OpenApiCustomizer noticeOperations() {
        return api -> {
            boolean openApi31 = api.getOpenapi().startsWith("3.1");
            describeTag(api, "공개 공지", "현재 게시된 공지의 목록과 상세를 로그인 없이 조회합니다.");
            describeTag(api, "관리자 공지", "공지 조회·등록·수정·삭제와 독립 이미지 업로드를 제공합니다. " + ADMIN_ACCESS);
            var publicSummary = notice(false, false, openApi31);
            var publicDetail = notice(true, false, openApi31);
            var adminNotice = notice(true, true, openApi31);
            var summary = noticeExample(false, false);
            var detail = noticeExample(true, false);
            var admin = noticeExample(true, true);

            var publicList = api.getPaths().get(PUBLIC).getGet();
            describe(publicList, "listPublicNotices", "공개 공지 목록", """
                    로그인과 필수 약관 재동의 없이 조회할 수 있습니다. 게시 취소·미게시·미래 게시·논리 삭제된 공지는 제외합니다.
                    고정 공지가 항상 우선합니다. latest는 pinned DESC, publishedAt DESC, noticeId DESC,
                    oldest는 pinned DESC, publishedAt ASC, noticeId ASC입니다.
                    예시 요청은 GET /api/v1/notices?sort=latest&limit=20 입니다.
                    """ + CURSOR, false);
            listParameters(publicList, false);
            pageResponse(publicList, publicSummary, summary, openApi31);
            listErrors(publicList);
            commonErrors(publicList, false, false);

            var publicRead = api.getPaths().get(PUBLIC + ID_PATH).getGet();
            describe(publicRead, "getPublicNotice", "공개 공지 상세", """
                    로그인과 재동의 없이 현재 게시된 공지를 UUID로 조회합니다.
                    게시 취소·미게시·미래 게시·논리 삭제되었거나 존재하지 않으면 404입니다.
                    """ + MARKDOWN, false);
            publicRead.addParametersItem(noticeId());
            success(publicRead, "200", "공개 공지 상세", publicDetail, detail);
            detailErrors(publicRead);
            commonErrors(publicRead, false, false);

            var adminList = api.getPaths().get(ADMIN).getGet();
            describe(adminList, "listAdminNotices", "관리자 공지 목록", ADMIN_ACCESS + """
                    미게시·게시 취소·미래 게시 공지도 조회할 수 있고, 논리 삭제된 공지는 제외합니다.
                    고정 여부와 관계없이 latest는 createdAt DESC, noticeId DESC, oldest는 createdAt ASC, noticeId ASC입니다.
                    createdAt은 정렬 기준으로 사용하며 응답 필드에는 포함되지 않습니다.
                    예시 요청은 GET /api/v1/admin/notices?sort=latest&limit=20 입니다.
                    쓰기 전 이 응답에서 CSRF 세션 쿠키와 X-CSRF-TOKEN을 받으세요. 기존 세션 쿠키는 계속 사용합니다.
                    프론트는 credentials: include로 브라우저가 쿠키를 유지하게 하고, CORS가 허용 Origin에 노출한
                    X-CSRF-TOKEN 응답 헤더를 읽으세요. Set-Cookie는 브라우저가 처리합니다.
                    """ + CURSOR, true);
            listParameters(adminList, true);
            pageResponse(adminList, adminNotice, admin, openApi31);
            adminList.getResponses().get("200")
                    .addHeaderObject("X-CSRF-TOKEN", new Header().description("관리자 공지 쓰기에 보낼 CSRF 토큰").schema(new StringSchema()))
                    .addHeaderObject("Set-Cookie", new Header().description("필요한 경우 응답에서 발급하는 CSRF 세션 쿠키").schema(new StringSchema()));
            listErrors(adminList);
            commonErrors(adminList, true, false);

            var adminRead = api.getPaths().get(ADMIN + ID_PATH).getGet();
            describe(adminRead, "getAdminNotice", "관리자 공지 상세", ADMIN_ACCESS + """
                    게시 여부와 시각에 관계없이 삭제되지 않은 공지를 UUID로 조회합니다.
                    """ + MARKDOWN, true);
            adminRead.addParametersItem(noticeId());
            success(adminRead, "200", "관리자 공지 상세", adminNotice, admin);
            detailErrors(adminRead);
            commonErrors(adminRead, true, false);

            var create = api.getPaths().get(ADMIN).getPost();
            describe(create, "createNotice", "공지 등록", WRITE_ACCESS + """
                    Content-Type은 application/json입니다. 다섯 필드를 모두 전달하세요.
                    제목과 본문은 공백만 입력할 수 없고 요약은 빈 문자열을 허용합니다.
                    published가 true이면 서버가 최초 게시 시각을 설정하고, false이면 publishedAt은 null입니다.
                    변경 사유는 요청에 포함하지 않습니다.
                    """ + MARKDOWN, true);
            csrf(create);
            create.setRequestBody(request(JSON, writeFields(true),
                    Map.of("title", "서비스 점검", "summary", "", "content", "**점검 안내**",
                            "pinned", false, "published", true)));
            var created = new LinkedHashMap<>(admin);
            created.put("pinned", false);
            success(create, "201", "공지 등록 완료", adminNotice, created);
            writeErrors(create, false);
            commonErrors(create, true, true);

            var patch = api.getPaths().get(ADMIN + ID_PATH).getPatch();
            describe(patch, "patchNotice", "공지 부분 수정", WRITE_ACCESS + """
                    Content-Type은 application/json입니다. 전달한 필드만 수정합니다.
                    최소 한 필드가 필요하고 명시적 null은 허용하지 않습니다.
                    처음 게시하면 publishedAt을 설정하고, 게시 취소·재게시 후에도 최초 값을 유지합니다.
                    updatedAt은 서버 시각으로 갱신합니다. 같은 필드는 마지막 DB 업데이트가 우선하며
                    version이나 If-Match를 요구하지 않습니다. 삭제된 공지는 수정할 수 없습니다.
                    변경 사유는 요청에 포함하지 않습니다.
                    """ + MARKDOWN, true);
            patch.addParametersItem(noticeId());
            csrf(patch);
            patch.setRequestBody(request(JSON, writeFields(false), Map.of("pinned", true)));
            success(patch, "200", "공지 수정 완료", adminNotice, admin);
            writeErrors(patch, true);
            commonErrors(patch, true, true);

            var delete = api.getPaths().get(ADMIN + ID_PATH).getDelete();
            describe(delete, "deleteNotice", "공지 논리 삭제", WRITE_ACCESS + """
                    행을 보존하고 deleted_at에 서버 UTC 시각을 기록해 공개·관리자 일반 조회에서 제외합니다.
                    이미 삭제되었거나 존재하지 않는 공지는 404입니다. 요청 본문 및 삭제 사유 입력은 없고
                    복구 API를 제공하지 않습니다. 성공하면 응답 본문 없이 204를 반환합니다.
                    """, true);
            delete.addParametersItem(noticeId());
            csrf(delete);
            delete.getResponses().addApiResponse("204", new ApiResponse().description("논리 삭제 완료. 응답 본문 없음."));
            detailErrors(delete);
            commonErrors(delete, true, true);

            upload(api.getPaths().get(ADMIN + "/images").getPost());
        };
    }

    private static void describeTag(OpenAPI api, String name, String description) {
        if (api.getTags() != null) {
            for (var tag : api.getTags()) {
                if (name.equals(tag.getName())) {
                    tag.setDescription(description);
                    return;
                }
            }
        }
        api.addTagsItem(new Tag().name(name).description(description));
    }

    private static void describe(Operation operation, String id, String summary, String description, boolean admin) {
        operation.operationId(id).summary(summary).description(description)
                .tags(List.of(admin ? "관리자 공지" : "공개 공지"));
        operation.setParameters(null);
        operation.setResponses(new ApiResponses());
    }

    private static void listParameters(Operation operation, boolean admin) {
        operation.addParametersItem(new Parameter().name("sort").in("query")
                .description("정렬. latest는 최신순, oldest는 오래된순입니다.")
                .schema(new StringSchema()._enum(List.of("latest", "oldest"))._default("latest")).example("latest"));
        var limit = new IntegerSchema()._default(20).example(20);
        limit.setMinimum(java.math.BigDecimal.ONE);
        limit.setMaximum(java.math.BigDecimal.valueOf(100));
        operation.addParametersItem(new Parameter().name("limit").in("query")
                .description("페이지 크기. 예를 들어 10, 20, 30개를 선택할 수 있습니다.").schema(limit));
        operation.addParametersItem(new Parameter().name("cursor").in("query")
                .description((admin ? "관리자" : "공개") + " 목록 전용 커서. 첫 페이지에서 생략합니다. 이후 같은 정렬의 nextCursor를 전달하세요.")
                .schema(new StringSchema().maxLength(admin ? 192 : 256)));
    }

    private static Parameter noticeId() {
        return new Parameter().name("noticeId").in("path").required(true)
                .description("하이픈을 포함한 표준 UUID 공지 식별자.").schema(new UUIDSchema()).example(NOTICE_ID);
    }

    private static void csrf(Operation operation) {
        operation.addParametersItem(new Parameter().name("X-CSRF-TOKEN").in("header").required(true)
                .description("관리자 목록 GET의 응답 토큰. 해당 CSRF 세션 쿠키도 같은 브라우저에서 전송해야 합니다.")
                .schema(new StringSchema()));
    }

    private static void upload(Operation operation) {
        describe(operation, "uploadNoticeImage", "공지 이미지 업로드", WRITE_ACCESS + """
                multipart/form-data의 file에 예를 들어 image/png 타입의 notice.png 한 장을 선택하세요.
                정적 JPEG(.jpg/.jpeg) 또는 PNG(.png)를 허용합니다. 파일 5 MiB, 요청 6 MiB,
                가로·세로 각각 4096픽셀 및 전체 8,388,608픽셀 이하입니다. 확장자, 선언 MIME, 시그니처와
                디코딩 결과를 검증하고, 위장 파일과 애니메이션, SVG, WebP를 거부합니다.
                PNG로 재인코딩해 최대 16 MiB−1까지 Notice 전용 DB에 저장한 뒤 201을 반환합니다.
                imageId는 저장 완료 식별용입니다. URL, 조회·다운로드·삭제, 공지 연결, 본문 삽입과 편집 기능은 제공하지 않습니다.
                """, true);
        csrf(operation);
        var file = new StringSchema().format("binary").description("정적 JPEG 또는 PNG 파일 한 장");
        operation.setRequestBody(request("multipart/form-data", object().addProperty("file", file)
                .required(List.of("file")), null));
        success(operation, "201", "이미지 저장 완료",
                object().addProperty("imageId", new UUIDSchema().description("저장 완료된 이미지 UUID")).required(List.of("imageId")),
                Map.of("imageId", "550e8400-e29b-41d4-a716-446655440000"));
        errors(operation, "400", INVALID_REQUEST, "파일 누락·중복·빈 파일, 잘못된 multipart 또는 요청 Content-Type");
        errors(operation, "413", "IMAGE_TOO_LARGE", "파일·요청·재인코딩 PNG 크기 한도 초과");
        errors(operation, "422",
                "INVALID_IMAGE", "지원하지 않는 유형, 확장자·MIME·내용 불일치 또는 손상된 이미지",
                "INVALID_IMAGE_DIMENSIONS", "가로·세로 또는 전체 픽셀 한도 위반",
                "ANIMATED_IMAGE_NOT_ALLOWED", "PNG 애니메이션 청크 발견");
        errors(operation, "503", "SERVICE_UNAVAILABLE", "인스턴스의 동시 업로드 처리 슬롯 두 개가 모두 사용 중");
        commonErrors(operation, true, true);
    }

    private static Schema<?> notice(boolean content, boolean admin, boolean openApi31) {
        var fields = object()
                .addProperty("noticeId", new UUIDSchema().description("공지 UUID"))
                .addProperty("title", new StringSchema().description("제목. 최대 200자").maxLength(200))
                .addProperty("summary", new StringSchema().description("목록 요약. 최대 500자").maxLength(500))
                .addProperty("pinned", new BooleanSchema().description("상단 고정 여부"))
                .addProperty("publishedAt", nullable(new DateTimeSchema().description(
                        admin ? "최초 게시 시각. 한 번도 게시되지 않았으면 null" : "최초 게시 시각"), admin, openApi31))
                .addProperty("updatedAt", new DateTimeSchema().description("마지막 갱신 시각"));
        if (content) fields.addProperty("content", new StringSchema().description("Markdown 원문. 최대 50000자").maxLength(50000));
        if (admin) fields.addProperty("published", new BooleanSchema().description("현재 게시 여부"));
        fields.setRequired(List.copyOf(fields.getProperties().keySet()));
        return fields;
    }

    private static Schema<?> nullable(Schema<?> schema, boolean allowed, boolean openApi31) {
        if (allowed) {
            if (openApi31) schema.setTypes(Set.of("string", "null"));
            else schema.setNullable(true);
        }
        return schema;
    }

    private static ObjectSchema object() {
        return new ObjectSchema();
    }

    private static Map<String, Object> noticeExample(boolean content, boolean admin) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("noticeId", NOTICE_ID);
        fields.put("title", "서비스 점검");
        fields.put("summary", "");
        fields.put("pinned", true);
        fields.put("publishedAt", TIME);
        fields.put("updatedAt", TIME);
        if (content) fields.put("content", "**점검 안내**");
        if (admin) fields.put("published", true);
        return fields;
    }

    private static Schema<?> writeFields(boolean create) {
        var fields = object()
                .addProperty("title", new StringSchema().description("제목. 공백만 입력할 수 없습니다.").minLength(1).maxLength(200))
                .addProperty("summary", new StringSchema().description("목록 요약. 빈 문자열을 허용합니다.").maxLength(500))
                .addProperty("content", new StringSchema().description("Markdown 원문. 공백만 입력할 수 없습니다.").minLength(1).maxLength(50000))
                .addProperty("pinned", new BooleanSchema().description("상단 고정 여부"))
                .addProperty("published", new BooleanSchema().description("게시 여부"));
        fields.setAdditionalProperties(false);
        if (create) fields.setRequired(List.copyOf(fields.getProperties().keySet()));
        else fields.setMinProperties(1);
        return fields;
    }

    private static RequestBody request(String type, Schema<?> schema, Object example) {
        var media = new MediaType().schema(schema);
        if (example != null) media.setExample(example);
        return new RequestBody().required(true).content(new Content().addMediaType(type, media));
    }

    private static void pageResponse(Operation operation, Schema<?> item, Object example, boolean openApi31) {
        var page = object()
                .addProperty("items", new ArraySchema().items(item).description("현재 페이지 공지"))
                .addProperty("nextCursor", nullable(new StringSchema().description("다음 요청용 커서. 마지막 페이지에서는 null"), true, openApi31))
                .addProperty("hasNext", new BooleanSchema().description("다음 페이지 존재 여부"))
                .required(List.of("items", "nextCursor", "hasNext"));
        var lastPage = new LinkedHashMap<String, Object>();
        lastPage.put("items", List.of(example));
        lastPage.put("nextCursor", null);
        lastPage.put("hasNext", false);
        success(operation, "200", "공지 목록", page, lastPage);
    }

    private static Schema<?> meta() {
        return object().addProperty("requestId", new UUIDSchema().description("응답의 요청 추적 UUID"))
                .required(List.of("requestId"));
    }

    private static void success(Operation operation, String status, String description, Schema<?> schema, Object example) {
        var body = object().addProperty("data", schema).addProperty("meta", meta()).required(List.of("data", "meta"));
        var media = new MediaType().schema(body).example(Map.of("data", example, "meta", Map.of("requestId", REQUEST_ID)));
        operation.getResponses().addApiResponse(status,
                new ApiResponse().description(description).content(new Content().addMediaType(JSON, media)));
    }

    private static void listErrors(Operation operation) {
        errors(operation, "400", INVALID_REQUEST, "정수가 아닌 limit 또는 허용되지 않은 sort",
                "INVALID_CURSOR", "잘못되거나 변조된 커서, 다른 목록 또는 정렬에서 받은 커서");
        errors(operation, "422", VALIDATION_ERROR, "limit이 1~100 범위를 벗어남");
    }

    private static void detailErrors(Operation operation) {
        errors(operation, "400", INVALID_REQUEST, "표준 UUID 형식이 아닌 noticeId");
        errors(operation, "404", NOT_FOUND, "현재 호출자가 조회하거나 처리할 수 없는 공지");
    }

    private static void writeErrors(Operation operation, boolean patch) {
        errors(operation, "400", INVALID_REQUEST,
                (patch ? "잘못된 UUID, " : "") + "읽을 수 없는 JSON, JSON 객체가 아닌 본문 또는 알 수 없는 필드");
        errors(operation, "422", VALIDATION_ERROR,
                (patch ? "빈 PATCH, " : "필수 필드 누락, ") + "명시적 null, 잘못된 자료형, 길이 또는 공백 제약 위반");
        if (patch) errors(operation, "404", NOT_FOUND, "존재하지 않거나 논리 삭제된 공지");
    }

    private static void commonErrors(Operation operation, boolean admin, boolean write) {
        errors(operation, "401", "INVALID_CREDENTIALS", admin
                ? "인증 누락, 유효하지 않거나 만료된 JWT, 폐기·만료된 세션 또는 ACTIVE 상태가 아닌 회원"
                : "인증 없이 조회할 수 있습니다. 유효하지 않은 Bearer JWT나 폐기된 세션의 토큰을 보내면 인증 오류가 발생합니다.");
        if (admin) {
            if (write) {
                errors(operation, "403", "FORBIDDEN", "인증 후 현재 ADMIN 권한 확인 실패 또는 CSRF 검증 실패",
                        "TERMS_RECONSENT_REQUIRED", "필수 약관 재동의 필요",
                        "ORIGIN_NOT_ALLOWED", "Origin 검사에서 허용 출처 또는 브라우저 요청 조건 위반");
            } else {
                errors(operation, "403", "FORBIDDEN", "인증 후 현재 ADMIN 권한 확인 실패",
                        "TERMS_RECONSENT_REQUIRED", "필수 약관 재동의 필요");
            }
        } else {
            operation.getResponses().addApiResponse("403", new ApiResponse().description(
                    "허용되지 않은 Origin의 요청은 CORS 필터에서 거부될 수 있습니다. 공통 JSON 형식은 보장되지 않습니다."));
        }
        errors(operation, "409", "REQUEST_IN_PROGRESS", "DB 경합으로 처리하지 못함. Retry-After 이후 재시도할 수 있습니다.");
        operation.getResponses().get("409").addHeaderObject("Retry-After",
                new Header().description("재시도 전 대기 시간(초)").schema(new IntegerSchema().example(1)));
        errors(operation, "500", "INTERNAL_ERROR", "처리 중 예상치 못한 서버 오류");
    }

    private static void errors(Operation operation, String status, String... variants) {
        var error = object().addProperty("code", new StringSchema()).addProperty("message", new StringSchema())
                .addProperty("details", new ArraySchema().items(object())).required(List.of("code", "message", "details"));
        var envelope = object().addProperty("error", error).addProperty("meta", meta()).required(List.of("error", "meta"));
        var media = new MediaType().schema(envelope);
        var descriptions = new java.util.ArrayList<String>();
        for (int i = 0; i < variants.length; i += 2) {
            String code = variants[i];
            String reason = variants[i + 1];
            descriptions.add("`" + code + "` — " + reason);
            media.addExamples(code, new Example().summary(reason).value(Map.of(
                    "error", Map.of("code", code, "message", "요청을 처리할 수 없습니다.", "details", List.of()),
                    "meta", Map.of("requestId", REQUEST_ID))));
        }
        if (status.equals("403")) {
            descriptions.add("CORS 필터가 먼저 차단한 요청은 공통 JSON 없이 거부될 수 있습니다.");
        }
        operation.getResponses().addApiResponse(status, new ApiResponse().description(String.join("\n\n", descriptions))
                .content(new Content().addMediaType(JSON, media)));
    }
}
