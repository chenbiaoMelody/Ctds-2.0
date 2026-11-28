package com.ctds.catalog.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CatalogRateLimitException;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 码表 ↔ 出站处理器集合一致性锚（WBS-3.3.2 hifi §2；沿 space 同名测试先例）：
 * CatalogErrorCodes 登记的每个码值都必须在 CatalogExceptionHandler.MAPPED_CODES 内
 * （否则 1007 段异常会落全局处理器按默认映射出站，HTTP 语义漂移）；反向亦不得多出幽灵码。
 */
class CatalogExceptionHandlerCodeConsistencyTest {

    @Test
    void handlerMappedCodesMatchErrorCodeTable() throws IllegalAccessException {
        final Set<String> declared = new TreeSet<>();
        for (final Field field : CatalogErrorCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ErrorCode.class) {
                declared.add(((ErrorCode) field.get(null)).value());
            }
        }
        assertThat(declared).as("1007 段码表应登记 22 个 C 码 + 2 个 S 码（WBS-3.3.5 +7，DB-134 +1："
                        + "1007C0015~0022）").hasSize(24);
        assertThat(new TreeSet<>(CatalogExceptionHandler.MAPPED_CODES))
                .as("处理器映射集与码表必须逐项一致（不得漂移）")
                .isEqualTo(declared);
        assertThat(declared).allMatch(code -> code.startsWith("1007"));
    }

    @Test
    void unmappedCatalogCodeDelegatesToGlobalHandler() {
        final CatalogBizException exception = new CatalogBizException(
                ErrorCode.of("1007C9999"), "unmapped catalog code");
        assertThatThrownBy(() -> new CatalogExceptionHandler().onCatalogBizException(exception))
                .isInstanceOf(BizException.class)
                .hasMessage("unmapped catalog code");
    }

    @Test
    void catalogBusinessRateLimitCodeMapsTo429() {
        final ResponseEntity<com.ctds.common.api.ApiResult<Void>> response =
                new CatalogExceptionHandler().onCatalogBizException(new CatalogBizException(
                        ErrorCode.of("1007C0022"), "catalog rate limit"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("1007C0022");
    }

    @Test
    void rateLimitResponseCarriesRetryAfterAndUnifiedEnvelope() {
        final ResponseEntity<com.ctds.common.api.ApiResult<Void>> response =
                new CatalogExceptionHandler().onRateLimit(new CatalogRateLimitException(7));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
        assertThat(response.getBody().code()).isEqualTo("1007C0022");
        assertThat(response.getBody().message()).isEqualTo("对象访问频率超出限制");
        assertThat(response.getBody().data()).isNull();
    }
}
