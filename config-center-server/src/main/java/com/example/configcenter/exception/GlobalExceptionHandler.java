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
 * 全局异常翻译器。
 * 目标很简单：别把框架原生那种又长又碎的异常直接甩给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleBiz(BizException e) {

        ErrorCode ec = e.getErrorCode();
        org.springframework.http.HttpStatus status = org.springframework.http.HttpStatus.BAD_REQUEST;

        if (ec == ErrorCode.NOT_FOUND) {
            status = org.springframework.http.HttpStatus.NOT_FOUND;
        } else if (ec == ErrorCode.CONFLICT) {
            status = org.springframework.http.HttpStatus.CONFLICT;
        } else if (ec == ErrorCode.RATE_LIMIT) {
            status = org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
        }

        ApiResponse<?> body = ApiResponse.fail(ec.getCode(), e.getMessage());
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }

    // @Valid 校验请求体失败时，把字段名一并带回去，调用方更容易定位是哪一项没填对。
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            map.put(fe.getField(), fe.getDefaultMessage());
        }
        ApiResponse<ErrorResponse> resp = ApiResponse.fail(ErrorCode.PARAM_INVALID.getCode(), "参数校验失败");
        resp.setData(new ErrorResponse(map));
        return resp;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<?> handleConstraint(ConstraintViolationException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.getCode(), "请求参数错误: " + e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<?> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.getCode(), "缺少参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<?> handleBadJson(HttpMessageNotReadableException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.getCode(), "请求体 JSON 解析失败");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<?> handleConflict(DataIntegrityViolationException e) {
        return ApiResponse.fail(ErrorCode.CONFLICT.getCode(), "数据冲突，可能是唯一键重复或并发写入导致");
    }

    @ExceptionHandler({
            org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            org.springframework.dao.OptimisticLockingFailureException.class
    })
    public ApiResponse<?> handleOptimisticLock(Exception e) {
        return ApiResponse.fail(
                ErrorCode.CONFLICT.getCode(),
                "并发更新冲突（optimistic lock），请刷新后重试"
        );
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        ApiResponse<?> body = ApiResponse.fail(ErrorCode.NOT_FOUND.getCode(), "资源不存在: " + e.getResourcePath());
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(body);
    }

    // 最后一层兜底，至少保证返回结构别突然变形。
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleOthers(Exception e) {
        ApiResponse<?> body = ApiResponse.fail(
                ErrorCode.SYSTEM_ERROR.getCode(),
                "系统异常: " + e.getClass().getSimpleName()
        );
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }
}
