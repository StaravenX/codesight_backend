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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该账号已存在");
        }
        if ((scene == VerificationScene.LOGIN || scene == VerificationScene.RESET_PASSWORD) && !exists) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号不存在，请先注册");
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

        // 1. 校验是否同意协议
        if (!request.agreeTerms()) {
            throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
        }

        // 2. 校验标识合法性
        validateIdentifier(request.identifierType(), request.identifier());

        // 3. 标准化清洗账号标识
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());

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

        return new AuthResponse(new AuthUserResponse(user), new TokenResponse(tokenPair));
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

        normalizeIdentifier(type, identifier);
        validateIdentifier(type, identifier);

        User user = findByIdentifier(type, identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "密码错误");
        }

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        loginLogService.save(user.getId(), identifier, LoginChannel.PASSWORD, clientInfo.ip(), clientInfo.userAgent(),
                LoginStatus.SUCCESS);
                
        return new AuthResponse(new AuthUserResponse(user), new TokenResponse(tokenPair));
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

        normalizeIdentifier(type, identifier);
        validateIdentifier(type, identifier);

        User user = findByIdentifier(type, identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在"));

        verificationService.ensureVerified(VerificationScene.LOGIN, identifier, request.code());

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        loginLogService.save(user.getId(), identifier, LoginChannel.CODE, clientInfo.ip(), clientInfo.userAgent(),
                LoginStatus.SUCCESS);

        return new AuthResponse(new AuthUserResponse(user), new TokenResponse(tokenPair));
    }

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
