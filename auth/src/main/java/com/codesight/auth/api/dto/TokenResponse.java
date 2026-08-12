package com.codesight.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import com.codesight.auth.token.TokenPair;

/**
 * 令牌响应。
 * <p>
 * 返回访问令牌与刷新令牌及其过期时间，供客户端持久化与后续调用使用。
 */
@Schema(description = "令牌响应")
public record TokenResponse(
        @Schema(description = "访问令牌 (Access Token)") String accessToken,
        @Schema(description = "访问令牌过期时间") Instant accessTokenExpiresAt,
        @Schema(description = "刷新令牌 (Refresh Token)") String refreshToken,
        @Schema(description = "刷新令牌过期时间") Instant refreshTokenExpiresAt
) {
    public TokenResponse(TokenPair pair) {
        this(pair.accessToken(), pair.accessTokenExpiresAt(), pair.refreshToken(), pair.refreshTokenExpiresAt());
    }
}
