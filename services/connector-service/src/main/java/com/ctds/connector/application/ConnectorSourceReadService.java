package com.ctds.connector.application;

import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.ConnectorAdapterProbeException;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorSource;
import com.ctds.connector.domain.ConnectorSourceRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorUnavailableException;
import com.ctds.connector.domain.SourceAdapterCatalog;
import com.ctds.connector.domain.SourceEndpointPolicy;
import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 源数据读取编排（规格行为 14；hifi §7-④）：<b>仅使用控制判定放行后由交付链调用</b>（判定次序归 3.6.8）。
 *
 * <p>本地前置四校验：连接器已注册（否则 {@code 1010C0004}）→ 源存在且属于该连接器（否则 {@code 1010C0003}，
 * 防枚举）→ 参数合法（{@code 1010C0001}）→ 类型有适配器（否则 {@code 1010C0011}）。
 * <b>前置拒绝时适配器零调用</b>（可证伪断言 hifi §9 T8）。适配器返回失败 = 200 业务结论（不是接口错误）；
 * 返回摘要<b>不含数据本体与原文</b>，留痕五要素：操作者 / 连接器 / 源 / 合约 / 结果。</p>
 */
@Service
public class ConnectorSourceReadService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorSourceReadService.class);
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int PURPOSE_MAX = 128;
    private static final int DETAIL_MAX = 240;
    private static final String READ_FAILED = "READ_FAILED";

    private final ConnectorSourceRepository sourceRepository;
    private final ConnectorRepository connectorRepository;
    private final SourceAdapterCatalog adapterCatalog;
    private final ConnectorAuditService audit;
    private final boolean allowPrivateEndpoint;

    public ConnectorSourceReadService(final ConnectorSourceRepository sourceRepository,
            final ConnectorRepository connectorRepository, final SourceAdapterCatalog adapterCatalog,
            final ConnectorAuditService audit,
            @Value("${ctds.connector.source.allow-private-endpoint:false}")
            final boolean allowPrivateEndpoint) {
        this.sourceRepository = sourceRepository;
        this.connectorRepository = connectorRepository;
        this.adapterCatalog = adapterCatalog;
        this.audit = audit;
        this.allowPrivateEndpoint = allowPrivateEndpoint;
    }

    /** 读取源数据（内部面；返回摘要与读取时间，不含数据本体）。 */
    public ReadOutcome read(final String operatorNo, final String connectorNo, final String sourceNo,
            final String contractNo, final String purpose) {
        final String normalizedContractNo = normalizeContractNo(contractNo);
        final String normalizedPurpose = normalizePurpose(purpose);
        final ConnectorRegistration connector = connectorRepository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorSourceReadService::notFound);
        if (connector.status() != ConnectorStatus.REGISTERED) {
            throw stateNotAllowed();
        }
        final ConnectorSource source = sourceRepository.findBySourceNo(sourceNo)
                .filter(item -> item.connectorNo().equals(connectorNo))
                .orElseThrow(ConnectorSourceReadService::notFound);
        final SourceType type = parseType(source.sourceType())
                .orElseThrow(ConnectorSourceReadService::unsupported);
        final SourceAdapter adapter = adapterCatalog.find(type)
                .orElseThrow(ConnectorSourceReadService::unsupported);
        final String endpoint = SourceEndpointPolicy.normalize(type, source.endpoint(), allowPrivateEndpoint);
        final SourceReadResult result;
        try {
            result = adapter.read(new SourceRegistration(type, endpoint, source.credentialRef(),
                    connectorNo), new SourceReadRequest(normalizedContractNo, normalizedPurpose));
        } catch (final ConnectorUnavailableException e) {
            throw e;
        } catch (final RuntimeException e) {
            LOG.warn("源读取适配器非预期失败（已收敛为 502）：{}", e.getClass().getName());
            throw new ConnectorAdapterProbeException(e);
        }
        final LocalDateTime readAt = LocalDateTime.now();
        final String detail = detail("sourceNo=" + sourceNo, "contractNo=" + normalizedContractNo,
                "success=" + result.success(), "summary=" + result.summary());
        if (result.success()) {
            audit.success(ConnectorAuditActions.SOURCE_READ, null, connectorNo, operatorNo, detail);
        } else {
            audit.failure(ConnectorAuditActions.SOURCE_READ, connectorNo, operatorNo, READ_FAILED, detail);
        }
        return new ReadOutcome(result, readAt);
    }

    private static String normalizeContractNo(final String contractNo) {
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw paramInvalid();
        }
        return contractNo;
    }

    private static String normalizePurpose(final String purpose) {
        if (purpose == null) {
            return null;
        }
        final String trimmed = purpose.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > PURPOSE_MAX) {
            throw paramInvalid();
        }
        return trimmed;
    }

    private static Optional<SourceType> parseType(final String value) {
        try {
            return Optional.of(SourceType.valueOf(value));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String detail(final String... parts) {
        final String text = String.join(";", parts);
        return text.length() <= DETAIL_MAX ? text : text.substring(0, DETAIL_MAX);
    }

    private static ConnectorBizException paramInvalid() {
        return new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }

    private static ConnectorBizException stateNotAllowed() {
        return new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
    }

    private static ConnectorBizException unsupported() {
        return new ConnectorBizException(ConnectorErrorCodes.SOURCE_ADAPTER_UNSUPPORTED,
                ConnectorErrorCodes.SOURCE_ADAPTER_UNSUPPORTED_MESSAGE);
    }

    /** 读取结论 + 读取时间（留痕时间口径）。 */
    public record ReadOutcome(SourceReadResult result, LocalDateTime readAt) {
    }
}
