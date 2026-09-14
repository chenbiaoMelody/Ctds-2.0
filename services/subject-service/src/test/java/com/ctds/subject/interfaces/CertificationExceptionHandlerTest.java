package com.ctds.subject.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.api.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 容器级上传超限封套直测（WBS-3.1.6 T6 的另一半）：MockMvc 不经过真实 multipart 解析器，
 * 容器上限（6MB）抛出的 MaxUploadSizeExceededException 无法从 HTTP 层触发——此处直测处理器
 * 映射口径（400 + 1000C0001 + 与业务 5MB 上限同文案族，评审视角 2 修复语义固化）。
 */
class CertificationExceptionHandlerTest {

    @Test
    void containerOversizeUploadMapsToUnifiedParamEnvelope() {
        final ResponseEntity<ApiResult<Void>> response =
                new CertificationExceptionHandler()
                        .onUploadTooLarge(new MaxUploadSizeExceededException(6_291_456L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("1000C0001");
        assertThat(response.getBody().message()).isEqualTo("证照影像大小超出上限（≤5MB）");
        assertThat(response.getBody().data()).isNull();
    }
}
