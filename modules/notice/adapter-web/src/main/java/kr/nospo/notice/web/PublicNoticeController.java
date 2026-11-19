package kr.nospo.notice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.nospo.notice.application.PublicNoticeService;
import kr.nospo.notice.contract.NoticeDetail;
import kr.nospo.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공개 공지")
@RestController
@RequestMapping("/api/v1/notices")
public class PublicNoticeController {
    private final PublicNoticeService notices;

    public PublicNoticeController(PublicNoticeService notices) {
        this.notices = notices;
    }

    @Operation(summary = "공지 목록", description = "게시된 공지를 고정 우선 순서로 조회한다. 최신순과 오래된순을 지원한다.")
    @GetMapping
    public ApiResponse<PublicNoticeService.Page> list(
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String sort) {
        return ApiResponse.of(notices.list(cursor, NoticeRequest.limit(limit), NoticeRequest.sort(sort)));
    }

    @Operation(summary = "공지 상세", description = "현재 공개할 수 있는 공지와 Markdown 본문을 반환한다.")
    @GetMapping("/{noticeId}")
    public ApiResponse<NoticeDetail> detail(@PathVariable String noticeId) {
        return ApiResponse.of(notices.detail(NoticeRequest.id(noticeId)));
    }
}
