package com.codesight.auth.token;

import com.codesight.auth.config.AuthProperties;
import com.codesight.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JwtServiceTest {

    @Mock
    private JwtEncoder jwtEncoder;
    @Mock
    private JwtDecoder jwtDecoder;
    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Captor
    private ArgumentCaptor<JwtEncoderParameters> jwtParametersCaptor;

    private JwtService jwtService;
    private Instant fixedInstant;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("codesight");
        properties.getJwt().setAccessTokenTtl(Duration.ofMinutes(15));
        properties.getJwt().setRefreshTokenTtl(Duration.ofDays(7));

        fixedInstant = Instant.parse("2026-08-12T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        jwtService = new JwtService(jwtEncoder, jwtDecoder, properties, refreshTokenStore, fixedClock);
    }

    @Test
    void issueTokenPair_ShouldUseInjectedClockAndConfiguredDurations() {
        User user = new User();
        user.setId(1001L);
        user.setNickname("TestUser");

        Jwt mockJwt = mock(Jwt.class);
        when(mockJwt.getTokenValue()).thenReturn("mocked-token-value");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);

        TokenPair tokenPair = jwtService.issueTokenPair(user);

        assertNotNull(tokenPair);
        assertEquals("mocked-token-value", tokenPair.accessToken());
        assertEquals("mocked-token-value", tokenPair.refreshToken());

        assertEquals(fixedInstant.plus(Duration.ofMinutes(15)), tokenPair.accessTokenExpiresAt());
        assertEquals(fixedInstant.plus(Duration.ofDays(7)), tokenPair.refreshTokenExpiresAt());

        verify(jwtEncoder, times(2)).encode(jwtParametersCaptor.capture());
        JwtClaimsSet accessClaims = jwtParametersCaptor.getAllValues().get(0).getClaims();
        JwtClaimsSet refreshClaims = jwtParametersCaptor.getAllValues().get(1).getClaims();

        assertEquals(fixedInstant, accessClaims.getIssuedAt());
        assertEquals(fixedInstant.plus(Duration.ofMinutes(15)), accessClaims.getExpiresAt());
        assertEquals("access", accessClaims.getClaim("token_type"));

        assertEquals(fixedInstant, refreshClaims.getIssuedAt());
        assertEquals(fixedInstant.plus(Duration.ofDays(7)), refreshClaims.getExpiresAt());
        assertEquals("refresh", refreshClaims.getClaim("token_type"));

        verify(refreshTokenStore).storeToken(eq(1001L), eq(tokenPair.refreshTokenId()), eq(Duration.ofDays(7)));
    }

}
