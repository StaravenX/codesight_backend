package com.codesight.auth.service;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.RandomUtil;
import com.codesight.auth.api.dto.*;
import java.util.Optional;
import com.codesight.auth.audit.LoginLogService;
import com.codesight.auth.audit.model.LoginChannel;
import com.codesight.auth.audit.model.LoginStatus;
import com.codesight.auth.config.AuthProperties;
import com.codesight.auth.model.ClientInfo;
import com.codesight.auth.model.IdentifierType;
import com.codesight.auth.token.JwtService;
import com.codesight.auth.token.TokenPair;
import com.codesight.auth.verification.VerificationService;
import com.codesight.auth.verification.model.SendCodeResult;
import com.codesight.auth.verification.model.VerificationScene;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.user.User;
import com.codesight.user.UserService;
import com.codesight.user.api.dto.UserProfileResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final VerificationService verificationService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final LoginLogService loginLogService;

    /**
     * 发送验证码并返回过期信息。
     * <p>
     * 注册场景要求标识不存在；登录/重置密码场景要求标识存在。
     *
     * @param request 请求体，包含：标识类型与值、场景。
     * @return 响应体，包含目标标识、场景与验证码过期秒数。
     * @throws BusinessException 当标识格式错误或存在性不符合场景要求时抛出。
     */
    public SendCodeResponse sendCode(@Valid SendCodeRequest request) {
        IdentifierType type = request.type();
        VerificationScene scene = request.scene();
        String identifier = normalizeIdentifier(type, request.identifier());

        validateIdentifier(type, identifier);
        boolean exists = findByIdentifier(type, identifier).isPresent();

        if (scene == VerificationScene.REGISTER && exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS, "该账号已存在");
        }
        if ((scene == VerificationScene.LOGIN || scene == VerificationScene.RESET_PASSWORD) && !exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "账号不存在，请先注册");
        }

        SendCodeResult result = verificationService.sendCode(scene, identifier, type);
        return new SendCodeResponse(result.identifier(), result.scene(), result.expireSeconds());
    }

    /**
     * 注册用户并签发令牌。
     * <p>
     * 验证标识与验证码，创建用户（可选设置密码），记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    注册请求，包含：标识类型与值、验证码、可选密码、是否同意协议。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当未同意协议、标识冲突、验证码失败、密码不合规时抛出。
     */
    public AuthResponse register(RegisterRequest request, ClientInfo clientInfo) {
        String identifier = request.identifier();
        try {
            // 1. 校验是否同意协议
            if (!request.agreeTerms()) {
                throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
            }

            // 2. 校验标识合法性
            validateIdentifier(request.identifierType(), request.identifier());

            // 3. 标准化清洗账号标识
            identifier = normalizeIdentifier(request.identifierType(), request.identifier());

            // 4. 校验标识是否已存在
            if (findByIdentifier(request.identifierType(), identifier).isPresent()) {
                throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
            }

            // 5. 校验密码并加密（可选）
            String passwordHash = null;
            if (StringUtils.hasText(request.password())) {
                validatePassword(request.password());
                passwordHash = passwordEncoder.encode(request.password().trim());
            }

            // 6. 校验验证码是否正确
            verificationService.ensureVerified(VerificationScene.REGISTER, identifier, request.code());

            // 7. 构造用户信息
            User user = User.builder()
                    .phone(request.identifierType() == IdentifierType.PHONE ? identifier : null)
                    .email(request.identifierType() == IdentifierType.EMAIL ? identifier : null)
                    .passwordHash(passwordHash)
                    .nickname(StringUtils.hasText(request.nickname()) ? request.nickname() : "User_" + RandomUtil.randomString(8).toUpperCase())
                    .csId("geek_" + RandomUtil.randomString(8).toLowerCase())
                    .avatar("default-avatar.png")
                    .interestedDomains("[]")
                    .build();


            userService.save(user);

            // 8. 签发令牌并记录日志
            TokenPair tokenPair = jwtService.issueTokenPair(user);
            loginLogService.save(user.getId(), identifier, LoginChannel.REGISTER, clientInfo.ip(), clientInfo.userAgent(),
                    LoginStatus.SUCCESS);

            return new AuthResponse(UserProfileResponse.from(user), new TokenResponse(tokenPair));
        } catch (BusinessException e) {
            loginLogService.save(null, identifier, LoginChannel.REGISTER, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.FAILED);
            throw e;
        }
    }

    /**
     * 密码登录
     * @param request 登录请求，包含标识值、密码。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     */
    public AuthResponse loginByPassword(@Valid LoginByPasswordRequest request, ClientInfo clientInfo) {
        String identifier = request.identifier();
        String password = request.password();
        IdentifierType type = request.type();
        User user = null;

        try {
            normalizeIdentifier(type, identifier);
            validateIdentifier(type, identifier);
    
            user = findByIdentifier(type, identifier)
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "密码错误");
        }

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        loginLogService.save(user.getId(), identifier, LoginChannel.PASSWORD, clientInfo.ip(), clientInfo.userAgent(),
                LoginStatus.SUCCESS);
                
        return new AuthResponse(UserProfileResponse.from(user), new TokenResponse(tokenPair));
        } catch (BusinessException e) {
            Long userId = user != null ? user.getId() : null;
            loginLogService.save(userId, identifier, LoginChannel.PASSWORD, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.FAILED);
            throw e;
        }
    }

    /**
     * 验证码登录
     * @param request 登录请求，包含标识值、验证码。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     */
    public AuthResponse loginByCode(@Valid LoginByCodeRequest request, ClientInfo clientInfo) {
        String identifier = request.identifier();
        IdentifierType type = request.type();
        User user = null;

        try {
            normalizeIdentifier(type, identifier);
            validateIdentifier(type, identifier);
    
            user = findByIdentifier(type, identifier)
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在"));

            verificationService.ensureVerified(VerificationScene.LOGIN, identifier, request.code());

            TokenPair tokenPair = jwtService.issueTokenPair(user);
            loginLogService.save(user.getId(), identifier, LoginChannel.CODE, clientInfo.ip(), clientInfo.userAgent(),
                    LoginStatus.SUCCESS);

            return new AuthResponse(UserProfileResponse.from(user), new TokenResponse(tokenPair));
        } catch (BusinessException e) {
            Long userId = user != null ? user.getId() : null;
            loginLogService.save(userId, identifier, LoginChannel.CODE, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.FAILED);
            throw e;
        }
    }

    /**
     * 退出登录
     * 
     * @param request 退出登录请求
     */
    public void logout(LogoutRequest request) {
        String refreshToken = request.refreshToken();
        Jwt jwt = jwtService.decode(refreshToken);

        if (jwtService.extractTokenType(jwt).equals("access")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请使用 Refresh Token 进行登出");
        }

        long userId = jwtService.extractUserId(jwt);
        jwtService.revoke(userId, jwt.getId());
    }

    /**
     * 刷新 Refresh Token
     * @param request 刷新令牌请求
     * @param clientInfo 客户端信息
     * @return 新的令牌响应
     */
    public TokenResponse refresh(@Valid TokenRefreshRequest request, ClientInfo clientInfo) {
        String refreshToken = request.refreshToken();
        Jwt jwt = jwtService.decode(refreshToken);

        if (jwtService.extractTokenType(jwt).equals("access")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请使用 Refresh Token 进行刷新");
        }

        long userId = jwtService.extractUserId(jwt);
        String tokenId = jwt.getId();

        if(!jwtService.isTokenValid(userId, tokenId)) {
            loginLogService.save(userId, "System", LoginChannel.TOKEN_REFRESH, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.FAILED);
            
            jwtService.revokeAll(userId);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已被撤销");
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已被删除");
        }

        TokenPair newTokenPair = jwtService.issueTokenPair(user);
        jwtService.revoke(userId, tokenId);

        return new TokenResponse(newTokenPair);
    }

    /**
     * 重置密码
     * @param request 重置密码请求
     */
    public void resetPassword(@Valid PasswordResetRequest request, ClientInfo clientInfo) {
        String code = request.code();
        String newPassword = request.newPassword();
        String identifier = request.identifier();
        IdentifierType type = request.identifierType();
        User user = null;

        try {
            identifier = normalizeIdentifier(type, identifier);
            validateIdentifier(type, identifier);
            validatePassword(newPassword);

            user = findByIdentifier(type, identifier).orElse(null);
            if (user == null) {
                throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
            }

            if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "新密码不能与原密码相同");
            }

            verificationService.ensureVerified(VerificationScene.RESET_PASSWORD, identifier, code);

            String newPasswordHash = passwordEncoder.encode(newPassword.trim());
            user.setPasswordHash(newPasswordHash);
            userService.updateById(user);

            jwtService.revokeAll(user.getId());
            loginLogService.save(user.getId(), identifier, LoginChannel.PASSWORD_RESET, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.SUCCESS);
        } catch (BusinessException e) {
            Long userId = user != null ? user.getId() : null;
            loginLogService.save(userId, identifier, LoginChannel.PASSWORD_RESET, clientInfo.ip(), clientInfo.userAgent(), LoginStatus.FAILED);
            throw e;
        }
    }

    /**
     * 验证标识是否合法
     * 
     * @param type       标识类型
     * @param identifier 标识值
     */
    private void validateIdentifier(IdentifierType type, String identifier) {
        // 标示合法性判断
        if(type == IdentifierType.EMAIL && !Validator.isEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入正确的邮箱！");
        } else if(type == IdentifierType.PHONE && !Validator.isMobile(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入正确的手机号！");
        }
    }

    /**
     * 根据标识查找用户
     *
     * @param type 标识类型
     * @param identifier 标识
     * @return 包含用户的 Optional 对象
     */
    private Optional<User> findByIdentifier(IdentifierType type, String identifier) {
        User user = userService.lambdaQuery()
                .eq(type == IdentifierType.EMAIL ? User::getEmail : User::getPhone, identifier)
                .one();
        return Optional.ofNullable(user);
    }


    /**
     * 标准化清洗账号标识
     *
     * @param type 标识类型
     * @param identifier 标识
     * @return 标准化后的账号标识
     */
    private String normalizeIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.EMAIL) {
            identifier = identifier.toLowerCase();
        }
        return StrUtil.cleanBlank(identifier);
    }


    /**
     * 校验密码策略：非空、最小长度、必须包含字母和数字。
     *
     * @param password 明文密码。
     * @throws BusinessException 当密码不满足策略时抛出。
     */
    private void validatePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码不能为空");
        }
        String trimmed = password.trim();
        if (trimmed.length() < authProperties.getPassword().getMinLength()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码长度至少" + authProperties.getPassword().getMinLength() + "位");
        }
        boolean hasLetter = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码需包含字母和数字");
        }
    }

}
