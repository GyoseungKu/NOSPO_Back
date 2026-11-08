package kr.nospo.support;

import java.util.UUID;

public record ApiResponse<T>(T data, Meta meta) {
    public record Meta(UUID requestId) {}

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, new Meta(UUID.randomUUID()));
    }
}
