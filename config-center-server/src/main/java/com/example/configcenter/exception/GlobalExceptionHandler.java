package com.example.configcenter.exception;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标：把各种异常统一“翻译”为 ApiResponse（带 traceId）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：我们主动抛出的（可控）
     */
    @ExceptionHandler(BizException.class)
    public ApiResponse<?> handleBiz(BizException e) {
        return ApiResponse.fail(e.getErrorCode().code(), e.getMessage());
    }

    /**
     * @Valid 触发的 Body 校验失败（比如 @NotBlank）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            map.put(fe.getField(), fe.getDefaultMessage());
        }
        ApiResponse<ErrorResponse> resp = ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "参数校验失败");
        resp.setData(new ErrorResponse(map));
        return resp;
    }

    /**
     * Query 参数校验失败（@RequestParam 上的 @NotBlank 等）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<?> handleConstraint(ConstraintViolationException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "请求参数错误：" + e.getMessage());
    }

    /**
     * 缺少必要的 Query 参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<?> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "缺少参数：" + e.getParameterName());
    }

    /**
     * JSON 解析失败（比如 body 不是合法 JSON）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<?> handleBadJson(HttpMessageNotReadableException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "请求体 JSON 解析失败");
    }

    /**
     * 数据库层冲突：最常见是唯一键冲突（并发写 or 重复写策略不当）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<?> handleConflict(DataIntegrityViolationException e) {
        return ApiResponse.fail(ErrorCode.CONFLICT.code(), "数据冲突（可能是唯一键重复）");
    }
    @ExceptionHandler({
            org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            org.springframework.dao.OptimisticLockingFailureException.class
    })
    public com.example.configcenter.dto.ApiResponse<?> handleOptimisticLock(Exception e) {
        return com.example.configcenter.dto.ApiResponse.fail(
                com.example.configcenter.exception.ErrorCode.CONFLICT.code(),
                "并发更新冲突（optimistic lock），请重试"
        );
    }
    /**
     * 兜底：任何未预期异常
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleOthers(Exception e) {
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR.code(), "系统异常：" + e.getClass().getSimpleName());
    }
}