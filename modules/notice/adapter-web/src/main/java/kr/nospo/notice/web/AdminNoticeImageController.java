package kr.nospo.notice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.util.List;
import kr.nospo.notice.application.NoticeImageService;
import kr.nospo.notice.contract.NoticeImageUpload;
import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class AdminNoticeImageController {
    public static final String PATH = "/api/v1/admin/notices/images";
    private final NoticeImageService images;

    public AdminNoticeImageController(NoticeImageService images) {
        this.images = images;
    }

    @Operation(summary = "공지 이미지 업로드", description = "정적 JPEG 또는 PNG 한 장을 저장하고 imageId를 반환합니다.")
    @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoticeImageUpload> upload(@RequestPart("file") List<MultipartFile> files)
            throws IOException {
        if (files.size() != 1) throw new ApiException(400, "INVALID_REQUEST");
        var file = files.getFirst();
        try (var input = file.getInputStream()) {
            return ApiResponse.of(images.upload(
                    input, file.getOriginalFilename(), file.getContentType(), file.getSize()));
        }
    }
}
