package kr.nospo.member.web;

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

    @PatchMapping("/nickname")
    public ApiResponse<?> nickname(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Nickname input) {
        return ApiResponse.of(
                Map.of("nickname", service.nickname(member(jwt), session(jwt), input.nickname())));
    }

    @PatchMapping("/phone-number")
    public ApiResponse<?> phone(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Phone input) {
        return ApiResponse.of(
                Map.of(
                        "phoneNumber",
                        service.phone(member(jwt), session(jwt), input.phoneNumber()),
                        "phoneVerified",
                        false));
    }

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

    @DeleteMapping("/profile-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        service.deleteImage(member(jwt), session(jwt));
    }

    @GetMapping(value = "/profile-image", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] image(@AuthenticationPrincipal Jwt jwt) {
        return service.image(member(jwt), session(jwt));
    }
}
