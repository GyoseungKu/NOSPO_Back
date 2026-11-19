package kr.nospo.member.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.member.application.ProfileService;
import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Tag(name = "회원 프로필", description = "닉네임, 전화번호 및 프로필 이미지 관리")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
    public record Nickname(@NotBlank @Size(max = 100) String nickname) {}

    public record Phone(@NotBlank @Size(max = 32) String phoneNumber) {}

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    private UUID member(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }

    @Operation(summary = "닉네임 변경", description = "새 닉네임을 저장하고 변경된 nickname을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PatchMapping("/nickname")
    public ApiResponse<?> nickname(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Nickname input) {
        return ApiResponse.of(
                Map.of("nickname", service.nickname(member(jwt), session(jwt), input.nickname())));
    }

    @Operation(summary = "전화번호 변경", description = "전화번호를 저장합니다. 응답의 phoneVerified는 false이며 전화번호 인증을 수행하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PatchMapping("/phone-number")
    public ApiResponse<?> phone(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Phone input) {
        return ApiResponse.of(
                Map.of(
                        "phoneNumber",
                        service.phone(member(jwt), session(jwt), input.phoneNumber()),
                        "phoneVerified",
                        false));
    }

    @Operation(summary = "프로필 이미지 업로드", description = "multipart/form-data의 file 필드로 이미지를 제출합니다. 최대 5 MiB이며 저장된 profileImageUrl을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PutMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(
            @AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file)
            throws IOException {
        if (file.getSize() > 5 * 1024 * 1024) throw new ApiException(413, "IMAGE_TOO_LARGE");
        return ApiResponse.of(
                Map.of(
                        "profileImageUrl",
                        service.image(member(jwt), session(jwt), file.getBytes())));
    }

    @Operation(summary = "프로필 이미지 삭제", description = "로그인한 회원의 프로필 이미지를 삭제합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 (응답 본문 없음)")
    @DeleteMapping("/profile-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        service.deleteImage(member(jwt), session(jwt));
    }

    @Operation(summary = "프로필 이미지 조회", description = "로그인한 회원의 프로필 이미지를 image/png 바이너리로 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping(value = "/profile-image", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] image(@AuthenticationPrincipal Jwt jwt) {
        return service.image(member(jwt), session(jwt));
    }
}
