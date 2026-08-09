package com.codesight.common.web;

import com.codesight.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * <p>
 * 统一接管 Controller 层及下层抛出的所有异常，
 * 将它们翻译为带有标准 HTTP 状态码的 Result 包装体返回给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截业务异常 (BusinessException)。
     * 将其转换为 HTTP 400 状态码，并提取具体的业务 ErrorCode 返回。
     *
     * @param e 抛出的业务异常
     * @return 包含错误码和错误信息的标准化 Result 响应体
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(Result.error(e.getErrorCode().getCode(), e.getMessage()));
    }

    /**
     * 拦截参数校验异常 (MethodArgumentNotValidException)。
     * 提取 DTO 校验注解上配置的 message，返回给前端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        // 获取所有校验报错中的第一个报错信息
        String errorMsg = e.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.error("BAD_REQUEST", errorMsg));
    }

    /**
     * 拦截所有未处理的系统异常 (Exception)。
     * <p>
     * 兜底拦截器：在后台打印完整错误日志，并向前端屏蔽真实的报错堆栈（防黑客），
     * 统一返回 HTTP 500 及友好的提示文案。
     *
     * @param e 抛出的系统异常
     * @return 包含 500 内部错误的标准化 Result 响应体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未捕获的系统运行时异常:", e);
        return ResponseEntity.internalServerError().body(Result.error("INTERNAL_ERROR", "服务异常，请稍后重试"));
    }
}
