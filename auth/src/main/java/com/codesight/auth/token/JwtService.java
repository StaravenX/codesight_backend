package com.codesight.auth.token;

import lombok.RequiredArgsConstructor;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.codesight.auth.config.AuthProperties;
import com.codesight.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * JWT 令牌服务。
 * <p>
 * 功能：签发 Access/Refresh Token（RS256），解码 JWT，提取用户 ID、令牌类型与令牌 ID。
 * 声明：
 * - `token_type`：标识 access 或 refresh；
 * - `uid`：用户 ID；
 * - `jti`：令牌 ID（用作 Refresh Token 的白名单键）。
 * 过期时间：来自 `AuthProperties.jwt.accessTokenTtl` 与 `refreshTokenTtl`。
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "token_type";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final AuthProperties properties;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 为指定用户签发一对 Access/Refresh Token 并自动存入白名单。
     * <p>
     * 令牌类型通过 `token_type` 声明区分；Refresh Token 的 `jti` 用于白名单存储与撤销。
     * 过期时间取自配置 `AuthProperties.jwt`。
     *
     * @param user 用户实体。
     * @return 令牌对与对应过期时间及刷新令牌 ID。
     */
    public TokenPair issueTokenPair(User user) {
        String refreshTokenId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant accessExpiresAt = issuedAt.plus(properties.getJwt().getAccessTokenTtl());
        Instant refreshExpiresAt = issuedAt.plus(properties.getJwt().getRefreshTokenTtl());

        String accessToken = encode(user, issuedAt, accessExpiresAt, "access", UUID.randomUUID().toString());
        String refreshToken = encode(user, issuedAt, refreshExpiresAt, "refresh", refreshTokenId);
        
        refreshTokenStore.storeToken(user.getId(), refreshTokenId, properties.getJwt().getRefreshTokenTtl());
        
        return new TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, refreshTokenId);
    }

    /**
     * 撤销用户所有的 Refresh Token。
     *
     * @param userId 用户 ID。
     */
    public void revokeAll(long userId) {
        refreshTokenStore.revokeAll(userId);
    }

    /**
     * 撤销指定用户的指定 Refresh Token。
     *
     * @param userId         用户 ID。
     * @param tokenId        Refresh Token 的 ID (jti)。
     */
    public void revoke(long userId, String tokenId) {
        refreshTokenStore.revokeToken(userId, tokenId);
    }

    /**
     * 解码 JWT 字符串为 {@link Jwt}。
     *
     * @param token JWT 字符串。
     * @return 解析后的 JWT 对象。
     */
    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    /**
     * 编码 JWT 令牌。
     *
     * @param user      用户实体。
     * @param issuedAt  签发时间。
     * @param expiresAt 过期时间。
     * @param tokenType 令牌类型（"access" 或 "refresh"）。
     * @param tokenId   令牌 ID（jti）。
     * @return 编码后的 JWT 字符串。
     */
    private String encode(User user, Instant issuedAt, Instant expiresAt, String tokenType, String tokenId) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .id(tokenId)
                .claim(CLAIM_TOKEN_TYPE, tokenType);

        if ("access".equals(tokenType)) {
            builder.claim("nickname", user.getNickname());
        }

        return jwtEncoder.encode(JwtEncoderParameters.from(builder.build())).getTokenValue();
    }

    /**
     * 从 JWT 中提取用户 ID。
     *
     * @param jwt 已解析的 JWT。
     * @return 用户 ID（long）。
     */
    public long extractUserId(Jwt jwt) {
        String subject = jwt.getSubject();
        return Long.parseLong(subject);
    }

    /**
     * 提取令牌类型声明。
     *
     * @param jwt 已解析的 JWT。
     * @return 令牌类型字符串（例如："access" 或 "refresh"）。
     */
    public String extractTokenType(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM_TOKEN_TYPE);
        return claim != null ? claim.toString() : "";
    }

    /**
     * 检查 JWT 是否有效。
     *
     * @param userId 用户 ID。
     * @param tokenId 令牌 ID。
     * @return 如果令牌有效则返回 true，否则返回 false。
     */
    public boolean isTokenValid(Long userId, String tokenId) {
        return refreshTokenStore.isTokenValid(userId, tokenId);
    }

}
