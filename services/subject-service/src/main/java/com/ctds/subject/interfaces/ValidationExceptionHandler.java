package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 参数校验异常出站（hifi B3：逐字段提示）：common GlobalExceptionHandler 未覆盖校验异常类型
 * （落入兜底 500 会失真），本服务内补充映射为 1000C0001 + 逐字段中文原因拼接，common 组件零改动。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> onInvalid(final MethodArgumentNotValidException ex) {
        final Set<String> problems = new LinkedHashSet<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> problems.add(error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), String.join("；", problems),
                        ApiResult.currentTraceId(), null));
    }
}
