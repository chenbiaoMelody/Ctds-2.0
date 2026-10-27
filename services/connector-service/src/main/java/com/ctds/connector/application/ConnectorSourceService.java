package com.ctds.connector.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.ConnectorAdapterProbeException;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorSource;
import com.ctds.connector.domain.ConnectorSourceCheck;
import com.ctds.connector.domain.ConnectorSourceRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorUnavailableException;
import com.ctds.connector.infrastructure.CredentialCipher;
import com.ctds.connector.infrastructure.SourceAdapterRegistry;
import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 源接入登记 / 变更 / 清单 / 详情 / 自检编排（规格行为 11~13；hifi §7-①②③）。
 *
 * <p>身份一律由调用方从鉴权上下文取（请求体不含主体编号）；归属与防枚举：非属主 / 不存在 /
 * 跨连接器一律同码同文 {@code 1010C0003}。凭据经 {@link CredentialCipher} 国密加密，任何对外形态只有掩码。
 * 自检失败 = 200 业务结论（三类原因可读 + 失败留痕）；适配器非预期异常 = 502 中性文案。</p>
 */
@Service
public class ConnectorSourceService {

    private static final String MOCK_SCHEME = "mock://";
    private static final int ENDPOINT_MAX = 512;
    private static final int CREDENTIAL_MAX = 256;
    private static final int DETAIL_MAX = 240;
    private static final String TEXT_NULL = "null";

    private final ConnectorSourceRepository sourceRepository;
    private final ConnectorRepository connectorRepository;
    private final CredentialCipher cipher;
    private final SourceAdapterRegistry adapterRegistry;
    private final ConnectorAuditService audit;

    public ConnectorSourceService(final ConnectorSourceRepository sourceRepository,
            final ConnectorRepository connectorRepository, final CredentialCipher cipher,
            final SourceAdapterRegistry adapterRegistry, final ConnectorAuditService audit) {
        this.sourceRepository = sourceRepository;
        this.connectorRepository = connectorRepository;
        this.cipher = cipher;
        this.adapterRegistry = adapterRegistry;
        this.audit = audit;
    }

    /** 源接入登记（四类源均受理；签发 SR+6 位；凭证密文 + 掩码落库；全动作留痕）。 */
    @Transactional
    public ConnectorSource register(final String subjectNo, final String connectorNo,
            final SourceType sourceType, final String endpoint, final String credential) {
        requireRegisteredOwned(subjectNo, connectorNo);
        final String normalizedEndpoint = normalizeEndpoint(sourceType, endpoint);
        final String normalizedCredential = normalizeCredential(credential);
        if (sourceRepository.existsDuplicate(connectorNo, sourceType.name(), normalizedEndpoint)) {
            audit.denied(ConnectorAuditActions.SOURCE_DENIED, null, connectorNo, subjectNo,
                    ConnectorErrorCodes.PARAM_INVALID.value(), "action=source.register");
            throw paramInvalid();
        }
        final String ciphertext = cipher.encrypt(normalizedCredential);
        final String masked = CredentialCipher.mask(normalizedCredential);
        final String sourceNo = sourceRepository.nextSourceNo();
        final String credentialRef = newCredentialRef();
        try {
            sourceRepository.insert(new ConnectorSource(null, sourceNo, connectorNo, subjectNo,
                    sourceType.name(), normalizedEndpoint, credentialRef, ciphertext, masked, null, null));
        } catch (final DuplicateKeyException e) {
            audit.denied(ConnectorAuditActions.SOURCE_DENIED, null, connectorNo, subjectNo,
                    ConnectorErrorCodes.PARAM_INVALID.value(), "action=source.register;duplicate=true");
            throw paramInvalid();
        }
        audit.success(ConnectorAuditActions.SOURCE_REGISTER, null, connectorNo, subjectNo,
                detail("sourceNo=" + sourceNo, "type=" + sourceType.name(),
                        "endpoint=" + normalizedEndpoint, "masked=" + masked));
        return readBack(sourceNo);
    }

    /** 源配置变更（地址必填；凭据可选——不传 = 不变；留痕记录旧 / 新要素，凭据只记掩码）。 */
    @Transactional
    public ConnectorSource amend(final String subjectNo, final String connectorNo, final String sourceNo,
            final String endpoint, final String credential) {
        requireRegisteredOwned(subjectNo, connectorNo);
        final ConnectorSource current = requireSource(connectorNo, sourceNo);
        final SourceType type = parseType(current.sourceType()).orElseThrow(ConnectorSourceService::unsupported);
        final String normalizedEndpoint = normalizeEndpoint(type, endpoint);
        if (!normalizedEndpoint.equals(current.endpoint())
                && sourceRepository.existsDuplicate(connectorNo, type.name(), normalizedEndpoint)) {
            throw paramInvalid();
        }
        final boolean credentialChanged = credential != null && !credential.isBlank();
        String credentialRef = current.credentialRef();
        String ciphertext = current.credentialCiphertext();
        String masked = current.credentialMasked();
        if (credentialChanged) {
            final String normalizedCredential = normalizeCredential(credential);
            ciphertext = cipher.encrypt(normalizedCredential);
            masked = CredentialCipher.mask(normalizedCredential);
            credentialRef = newCredentialRef();
        }
        sourceRepository.update(sourceNo, normalizedEndpoint, credentialRef, ciphertext, masked);
        audit.success(ConnectorAuditActions.SOURCE_AMEND, null, connectorNo, subjectNo,
                detail("sourceNo=" + sourceNo, "endpoint=" + current.endpoint() + "->" + normalizedEndpoint,
                        credentialChanged ? "credential=changed;masked=" + masked : "credential=unchanged"));
        return readBack(sourceNo);
    }

    /** 源清单（属主或运营方；不含自检结论，避免 N+1）。 */
    public List<ConnectorSource> list(final String callerNo, final boolean operator,
            final String connectorNo, final PageQuery page) {
        requireVisible(callerNo, operator, connectorNo);
        return sourceRepository.listByConnector(connectorNo, page.offset(), page.pageSize());
    }

    public long count(final String callerNo, final boolean operator, final String connectorNo) {
        requireVisible(callerNo, operator, connectorNo);
        return sourceRepository.countByConnector(connectorNo);
    }

    /** 源详情（含最近一次自检结论，可空；单次查询）。 */
    public SourceDetail detail(final String callerNo, final boolean operator, final String connectorNo,
            final String sourceNo) {
        requireVisible(callerNo, operator, connectorNo);
        final ConnectorSource source = requireSource(connectorNo, sourceNo);
        return new SourceDetail(source,
                sourceRepository.findLastCheck(connectorNo, sourceNo).orElse(null));
    }

    /** 连通性自检（只读；失败 = 200 业务结论 + 留痕；未开放类型 = 1010C0011）。 */
    public CheckOutcome check(final String subjectNo, final String connectorNo, final String sourceNo) {
        requireRegisteredOwned(subjectNo, connectorNo);
        final ConnectorSource source = requireSource(connectorNo, sourceNo);
        final SourceType type = parseType(source.sourceType()).orElseThrow(ConnectorSourceService::unsupported);
        final SourceAdapter adapter = adapterRegistry.find(type).orElseThrow(() -> {
            audit.denied(ConnectorAuditActions.SOURCE_DENIED, null, connectorNo, subjectNo,
                    ConnectorErrorCodes.SOURCE_ADAPTER_UNSUPPORTED.value(),
                    "action=source.check;sourceNo=" + sourceNo);
            return unsupported();
        });
        final SourceCheckResult result;
        try {
            result = adapter.check(new SourceRegistration(type, source.endpoint(),
                    source.credentialRef(), connectorNo));
        } catch (final ConnectorUnavailableException e) {
            throw e;
        } catch (final RuntimeException e) {
            audit.failure(ConnectorAuditActions.SOURCE_CHECK, connectorNo, subjectNo, "ADAPTER_ERROR",
                    detail("sourceNo=" + sourceNo, "passed=false", "failureType=" + TEXT_NULL,
                            "message=probe failed"));
            throw new ConnectorAdapterProbeException(e);
        }
        final LocalDateTime checkedAt = LocalDateTime.now();
        recordCheck(connectorNo, subjectNo, sourceNo, result);
        return new CheckOutcome(result, checkedAt);
    }

    private void recordCheck(final String connectorNo, final String subjectNo, final String sourceNo,
            final SourceCheckResult result) {
        final String failureType = result.passed() ? TEXT_NULL : result.failureType().name();
        final String detail = detail("sourceNo=" + sourceNo, "passed=" + result.passed(),
                "failureType=" + failureType, "message=" + result.message());
        if (result.passed()) {
            audit.success(ConnectorAuditActions.SOURCE_CHECK, null, connectorNo, subjectNo, detail);
            return;
        }
        audit.failure(ConnectorAuditActions.SOURCE_CHECK, connectorNo, subjectNo,
                result.failureType().name(), detail);
    }

    private ConnectorRegistration requireRegisteredOwned(final String subjectNo, final String connectorNo) {
        final ConnectorRegistration connector = requireVisible(subjectNo, false, connectorNo);
        if (connector.status() != ConnectorStatus.REGISTERED) {
            throw stateNotAllowed();
        }
        return connector;
    }

    /** 可见性口径（防枚举）：非属主 / 不存在一律同码同文；运营方读面放行（注销后仍可查，Q8-A）。 */
    private ConnectorRegistration requireVisible(final String callerNo, final boolean operator,
            final String connectorNo) {
        final ConnectorRegistration connector = connectorRepository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorSourceService::notFound);
        if (!operator && !connector.subjectNo().equals(callerNo)) {
            throw notFound();
        }
        return connector;
    }

    private ConnectorSource requireSource(final String connectorNo, final String sourceNo) {
        return sourceRepository.findBySourceNo(sourceNo)
                .filter(item -> item.connectorNo().equals(connectorNo))
                .orElseThrow(ConnectorSourceService::notFound);
    }

    private ConnectorSource readBack(final String sourceNo) {
        return sourceRepository.findBySourceNo(sourceNo)
                .orElseThrow(() -> new IllegalStateException("源登记读回失败"));
    }

    private static String normalizeEndpoint(final SourceType type, final String endpoint) {
        if (endpoint == null) {
            throw paramInvalid();
        }
        final String trimmed = endpoint.trim();
        if (trimmed.isEmpty() || trimmed.length() > ENDPOINT_MAX) {
            throw paramInvalid();
        }
        if (trimmed.startsWith(MOCK_SCHEME) && type != SourceType.GOV_EXCHANGE) {
            throw paramInvalid();
        }
        return trimmed;
    }

    private static String normalizeCredential(final String credential) {
        if (credential == null || credential.isBlank() || credential.length() > CREDENTIAL_MAX) {
            throw paramInvalid();
        }
        return credential;
    }

    private static Optional<SourceType> parseType(final String value) {
        try {
            return Optional.of(SourceType.valueOf(value));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String newCredentialRef() {
        return "SC" + UUID.randomUUID().toString().replace("-", "");
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

    /** 详情聚合（源 + 最近一次自检结论，可空）。 */
    public record SourceDetail(ConnectorSource source, ConnectorSourceCheck lastCheck) {
    }

    /** 自检结论 + 结论时间（留痕时间口径）。 */
    public record CheckOutcome(SourceCheckResult result, LocalDateTime checkedAt) {
    }
}
