package com.ctds.connector.interfaces.dto;

import com.ctds.connector.application.ConnectorSourceService.CheckOutcome;
import com.ctds.connector.application.ConnectorSourceService.SourceDetail;
import com.ctds.connector.domain.ConnectorSource;
import com.ctds.connector.domain.ConnectorSourceCheck;
import com.ctds.connector.sdk.spi.SourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 源接入请求 / 响应视图（hifi §4；interfaces 层转换，不外露内部实现字段）。
 *
 * <p>凭据只在请求出现（{@code WRITE_ONLY}，响应永不回显）；响应只有掩码与不透明引用。
 * null 字段不输出（清单不带自检结论、通过时无失败原因——防"存在但为空"的歧义）。</p>
 */
public final class ConnectorSourceViews {

    private ConnectorSourceViews() {
    }

    /** 源登记请求（四类源均受理；凭据仅请求侧；上限 1024 = 预签名 URL 形态）。 */
    public record RegisterSourceRequest(
            @NotNull SourceType sourceType,
            @NotBlank @Size(max = 512) String endpoint,
            @NotBlank @Size(max = 1024) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String credential) {
    }

    /** 源变更请求（地址必填；凭据空 = 不变；上限 1024）。 */
    public record AmendSourceRequest(
            @NotBlank @Size(max = 512) String endpoint,
            @Size(max = 1024) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String credential) {
    }

    /** 内部读取请求（判定放行后由交付链调用；WBS-3.6.8 W6 / DB-89：必填作业凭证 jobRef）。 */
    public record ReadRequest(
            @NotBlank @Size(max = 64) String contractNo,
            @Size(max = 128) String purpose,
            @NotBlank @Size(max = 64) String jobRef) {
    }

    /** 最近一次自检结论摘要。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckSummary(boolean passed, String failureType, String message,
            LocalDateTime checkedAt) {

        public static CheckSummary of(final ConnectorSourceCheck check) {
            return new CheckSummary(check.passed(), check.failureType(), check.message(), check.checkedAt());
        }
    }

    /** 源视图（清单不含自检结论；详情含最近一次自检）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceView(String sourceNo, String connectorNo, String sourceType, String endpoint,
            String credentialRef, String credentialMasked, LocalDateTime createdAt, LocalDateTime updatedAt,
            CheckSummary lastCheck) {

        public static SourceView summary(final ConnectorSource source) {
            return of(source, null);
        }

        public static SourceView of(final SourceDetail detail) {
            return of(detail.source(),
                    detail.lastCheck() == null ? null : CheckSummary.of(detail.lastCheck()));
        }

        private static SourceView of(final ConnectorSource source, final CheckSummary lastCheck) {
            return new SourceView(source.sourceNo(), source.connectorNo(), source.sourceType(),
                    source.endpoint(), source.credentialRef(), source.credentialMasked(),
                    source.createdAt(), source.updatedAt(), lastCheck);
        }
    }

    /** 自检结论视图（通过时无 failureType 字段）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceCheckView(String sourceNo, boolean passed, String failureType, String message,
            LocalDateTime checkedAt) {

        public static SourceCheckView of(final String sourceNo, final CheckOutcome outcome) {
            return new SourceCheckView(sourceNo, outcome.result().passed(),
                    outcome.result().passed() ? null : outcome.result().failureType().name(),
                    outcome.result().message(), outcome.checkedAt());
        }
    }

    /** 读取视图（摘要口径：不含数据本体与原文）。 */
    public record SourceReadView(String sourceNo, String connectorNo, String contractNo, boolean success,
            String summary, LocalDateTime readAt) {

        public static SourceReadView of(final String sourceNo, final String connectorNo,
                final String contractNo, final boolean success, final String summary,
                final LocalDateTime readAt) {
            return new SourceReadView(sourceNo, connectorNo, contractNo, success, summary, readAt);
        }
    }
}
