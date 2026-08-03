package com.codesight.auth.service;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;

import com.codesight.auth.api.dto.SendCodeRequest;
import com.codesight.auth.api.dto.SendCodeResponse;
import com.codesight.auth.model.IdentifierType;
import com.codesight.auth.verification.VerificationService;
import com.codesight.auth.verification.model.SendCodeResult;
import com.codesight.auth.verification.model.VerificationScene;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.user.User;
import com.codesight.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final VerificationService verificationService;
    private final UserService userService;

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

        // 标示合法性判断
        if(type == IdentifierType.EMAIL && !Validator.isEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入正确的邮箱！");
        } else if(type == IdentifierType.PHONE && !Validator.isMobile(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入正确的手机号！");
        }

        // 标识存在性查询
        boolean exists = userService.lambdaQuery()
                .eq(type == IdentifierType.EMAIL ? User::getEmail : User::getPhone, identifier)
                .exists();
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
     * 标准化清洗账号标识
     */
    private String normalizeIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.EMAIL) {
            identifier = identifier.toLowerCase();
        }
        return StrUtil.cleanBlank(identifier);
    }

}
