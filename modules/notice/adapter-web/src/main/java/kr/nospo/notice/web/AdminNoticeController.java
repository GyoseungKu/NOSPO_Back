package kr.nospo.notice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import kr.nospo.notice.application.AdminNoticeService;
import kr.nospo.notice.contract.AdminNotice;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@Tag(name = "관리자 공지")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/notices")
public class AdminNoticeController {
    private final AdminNoticeService notices;

    public AdminNoticeController(AdminNoticeService notices) {
        this.notices = notices;
    }

    @Operation(summary = "관리자 공지 목록", description = "삭제되지 않은 공지와 관리자 쓰기용 CSRF 응답 헤더를 반환한다.")
    @GetMapping
    public ApiResponse<AdminNoticeService.Page> list(
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String sort,
            @RequestAttribute("_csrf") CsrfToken csrf, HttpServletResponse response) {
        var page = notices.list(cursor, NoticeRequest.limit(limit), NoticeRequest.sort(sort));
        response.setHeader(csrf.getHeaderName(), csrf.getToken());
        return ApiResponse.of(page);
    }

    @Operation(summary = "관리자 공지 상세", description = "게시 상태와 관계없이 삭제되지 않은 공지 원본을 반환한다.")
    @GetMapping("/{noticeId}")
    public ApiResponse<AdminNotice> detail(@PathVariable String noticeId) {
        return ApiResponse.of(notices.detail(NoticeRequest.id(noticeId)));
    }

    @Operation(summary = "공지 등록", description = "제목, 요약, Markdown 본문, 고정 여부와 게시 여부를 저장한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminNotice> create(@RequestBody JsonNode input) {
        return ApiResponse.of(notices.create(AdminNoticeInput.parse(input, true)));
    }

    @Operation(summary = "공지 수정", description = "전달한 필드만 갱신하며 최초 게시 시각을 보존한다.")
    @PatchMapping("/{noticeId}")
    public ApiResponse<AdminNotice> patch(@PathVariable String noticeId, @RequestBody JsonNode input) {
        return ApiResponse.of(notices.patch(NoticeRequest.id(noticeId), AdminNoticeInput.parse(input, false)));
    }

    @Operation(summary = "공지 삭제", description = "삭제 시각을 기록하고 공개 및 관리자 조회에서 제외한다.")
    @DeleteMapping("/{noticeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String noticeId) {
        notices.delete(NoticeRequest.id(noticeId));
    }
}
