package com.ctds.delivery.application;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.idempotency.Idempotent;
import com.ctds.delivery.audit.DeliveryAuditActions;
import com.ctds.delivery.audit.DeliveryAuditService;
import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.CatalogPort;
import com.ctds.delivery.domain.ConnectorInternalException;
import com.ctds.delivery.domain.ConnectorInternalPort;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.ContractPort;
import com.ctds.delivery.domain.ContractSummary;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryActionLog;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.DeliveryType;
import com.ctds.delivery.domain.FileSource;
import com.ctds.delivery.domain.FileSourceRegistry;
import com.ctds.delivery.infrastructure.FilePackageStore;
import com.ctds.delivery.interfaces.dto.DeliveryViews.DeliveryView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 发起交付应用服务（hifi §3）。固定顺序：幂等查重 → 合约只读摘要（参与方+生效，不计数）→
 * 发起人=需求方 → 目录形态 → 形态/范围 →（FILE）策略 check → 加密包 → 记录。
 *
 * <p>API 交付发起不调用 check（真正调用由网关判定计数）；FILE 交付调用 check 一次（放行即计数）。
 * 任何拒绝/依赖不可用/加密失败均不写 delivery；拒绝留痕由 audit 单点承担。</p>
 */
@Service
public class DeliveryCommandService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCommandService.class);
    static final String RESULT_SUCCESS = "SUCCESS";
    private static final Set<String> FILE_FORMS = Set.of("DATASET", "REPORT", "MODEL");
    private static final Pattern CONTRACT_NO = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final Pattern SOURCE_NO = Pattern.compile("^SR\\d{6}$");
    private static final int MAX_ELEMENT_CHARS = 256;

    private final DeliveryRepository repository;
    private final ContractPort contractPort;
    private final CatalogPort catalogPort;
    private final ConnectorInternalPort connectorPort;
    private final FileSourceRegistry fileSourceRegistry;
    private final FilePackageStore packageStore;
    private final Sm4Service sm4Service;
    private final Sm3Service sm3Service;
    private final DeliveryPersistenceService persistence;
    private final DeliveryAuditService audit;
    private final DeliveryProperties properties;

    public DeliveryCommandService(final DeliveryRepository repository, final ContractPort contractPort,
            final CatalogPort catalogPort, final ConnectorInternalPort connectorPort,
            final FileSourceRegistry fileSourceRegistry,
            final FilePackageStore packageStore, final Sm4Service sm4Service,
            final Sm3Service sm3Service, final DeliveryPersistenceService persistence,
            final DeliveryAuditService audit, final DeliveryProperties properties) {
        this.repository = repository;
        this.contractPort = contractPort;
        this.catalogPort = catalogPort;
        this.connectorPort = connectorPort;
        this.fileSourceRegistry = fileSourceRegistry;
        this.packageStore = packageStore;
        this.sm4Service = sm4Service;
        this.sm3Service = sm3Service;
        this.persistence = persistence;
        this.audit = audit;
        this.properties = properties;
    }

    /** 发起交付（API 或 FILE）；幂等键 = requester + X-Idempotency-Key。 */
    @Idempotent(key = "#cmd.requesterNo + ':' + #cmd.idempotencyKey")
    public DeliveryView create(final DeliveryCommand cmd) {
        final String rawRequester = cmd == null || cmd.requesterNo() == null ? "" : cmd.requesterNo();
        final String rawContract = cmd == null || cmd.contractNo() == null ? "" : cmd.contractNo();
        String deliveryNo = null;
        String deliveryType = null;
        try {
            final String requester = requireText(rawRequester);
            final String contractNo = requireContractNo(rawContract);
            final DeliveryType type = parseType(cmd.deliveryType());
            deliveryType = type.name();
            final String idempotencyKey = requireIdempotencyKey(cmd.idempotencyKey());
            final String fileId = validateFileId(type, cmd.fileId());
            final String[] connectorFields = validateConnectorFields(type, cmd.connectorNo(),
                    cmd.sourceNo());
            final String purpose = validateElement(cmd.purpose());
            final String territory = validateElement(cmd.territory());

            final Optional<Delivery> replay = repository.findByIdempotencyKey(requester, idempotencyKey);
            if (replay.isPresent()) {
                return DeliveryView.from(replay.get());
            }

            final ContractSummary summary = contractPort.summarize(contractNo, requester);
            if (!requester.equals(summary.applicantNo())) {
                throw new DeliveryBizException(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
                        DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE);
            }
            final String productType = catalogPort.productType(summary.productId());
            validateForm(type, productType);

            FileSource source = null;
            if (type == DeliveryType.FILE) {
                source = fileSourceRegistry.require(fileId, summary.productId());
                contractPort.check(contractNo, requester, purpose, territory);
            } else if (type == DeliveryType.CONNECTOR) {
                // 先判定后取数（行为 14 规则 1）：判定拒绝即 DENIED 留痕，零占用、零触达源、零计量
                contractPort.check(contractNo, requester, purpose, territory);
            }

            deliveryNo = repository.nextDeliveryNo();
            if (type == DeliveryType.CONNECTOR) {
                // 占用登记（W4）：判定放行、编号签发后、取数前；jobRef = 交付编号
                occupyOrReject(connectorFields[0], deliveryNo);
            }
            String packageId = null;
            String checksum = null;
            String keyRef = null;
            Long fileSize = null;
            String connectorSummary = null;
            try {
                if (type == DeliveryType.FILE) {
                    final byte[] plaintext = readSource(source);
                    if (plaintext.length > properties.getFile().getMaxBytes()) {
                        throw new DeliveryBizException(DeliveryErrorCodes.FILE_TOO_LARGE,
                                DeliveryErrorCodes.FILE_TOO_LARGE_MESSAGE);
                    }
                    final byte[] envelope = encrypt(plaintext);
                    checksum = sm3Service.digestHex(plaintext);
                    packageId = deliveryNo + ".sm4";
                    packageStore.store(packageId, envelope);
                    keyRef = properties.getFile().getKeyRef();
                    fileSize = (long) plaintext.length;
                } else if (type == DeliveryType.CONNECTOR) {
                    connectorSummary = verifyOwnerAndRead(connectorFields[0], connectorFields[1],
                            deliveryNo, contractNo, purpose, summary);
                    // 包体 = 取数结果摘要文档（V1.0 演示形态：适配器读真实源产出摘要，不含数据本体；
                    // 数据本体传输腿归真实源演进），SM4 / SM3 / 密钥引用与 FILE 同口径
                    final byte[] plaintext = connectorSummary.getBytes(StandardCharsets.UTF_8);
                    final byte[] envelope = encrypt(plaintext);
                    checksum = sm3Service.digestHex(plaintext);
                    packageId = deliveryNo + ".sm4";
                    packageStore.store(packageId, envelope);
                    keyRef = properties.getFile().getKeyRef();
                    fileSize = (long) plaintext.length;
                }

                final Delivery delivery = new Delivery(null, deliveryNo, contractNo,
                        summary.productId(), summary.productName(), type, requester,
                        summary.providerNo(),
                        type == DeliveryType.CONNECTOR
                                ? connectorFields[0] + ":" + connectorFields[1]
                                : (source == null ? null : source.fileId()),
                        source == null ? null : source.displayName(), fileSize, packageId, checksum,
                        keyRef, RESULT_SUCCESS, idempotencyKey, null, null, null);
                try {
                    persistence.persist(delivery, new DeliveryActionLog(null, deliveryNo, contractNo,
                            requester, DeliveryAuditActions.CREATE, RESULT_SUCCESS, null, null));
                } catch (final DuplicateKeyException e) {
                    if (packageId != null) {
                        packageStore.deleteQuietly(packageId);
                    }
                    return repository.findByIdempotencyKey(requester, idempotencyKey)
                            .map(DeliveryView::from)
                            .orElseThrow(() -> new DeliveryBizException(
                                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, e));
                } catch (final RuntimeException e) {
                    if (packageId != null) {
                        packageStore.deleteQuietly(packageId);
                    }
                    throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                            DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, e);
                }
                audit.jsonOnly(DeliveryAuditActions.CREATE, deliveryNo, contractNo, requester,
                        deliveryType);
                return repository.findByDeliveryNo(deliveryNo).map(DeliveryView::from)
                        .orElseGet(() -> DeliveryView.from(delivery));
            } finally {
                // 释放兜底（W4）：落库成功后 + 全部失败路径 finally；幂等（重复释放安静成功）；
                // 进程崩溃残留 OCCUPY 为已知限制（hifi §1-B 事务边界），经占用清单端点可查可补
                if (type == DeliveryType.CONNECTOR) {
                    connectorPort.release(connectorFields[0], deliveryNo);
                }
            }
        } catch (final ContractPassThroughException e) {
            audit.denied(DeliveryAuditActions.DENIED, deliveryNo, rawContract, rawRequester,
                    e.getCode(), deliveryType);
            throw e;
        } catch (final DeliveryBizException e) {
            final String code = e.getErrorCode().value();
            // check 放行后（已取 deliveryNo）任何失败均为平台侧失败（hifi §5.4），不是用户拒绝
            if (deliveryNo != null || code.startsWith("1009S")) {
                audit.failure(DeliveryAuditActions.CREATE, deliveryNo, rawContract, rawRequester,
                        code, deliveryType);
            } else {
                audit.denied(DeliveryAuditActions.DENIED, deliveryNo, rawContract, rawRequester,
                        code, deliveryType);
            }
            throw e;
        }
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw paramInvalid();
        }
        return value.trim();
    }

    private static String requireContractNo(final String value) {
        final String text = requireText(value);
        if (!CONTRACT_NO.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static String requireIdempotencyKey(final String value) {
        final String text = requireText(value);
        if (!IDEMPOTENCY_KEY.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static DeliveryType parseType(final String value) {
        if (value == null) {
            throw paramInvalid();
        }
        try {
            return DeliveryType.valueOf(value.trim());
        } catch (final IllegalArgumentException e) {
            throw paramInvalid();
        }
    }

    private static String validateFileId(final DeliveryType type, final String value) {
        if (type == DeliveryType.FILE) {
            final String text = requireText(value);
            if (!FILE_ID.matcher(text).matches()) {
                throw paramInvalid();
            }
            return text;
        }
        // API / CONNECTOR（及经本方法的其余形态）禁带 fileId
        if (value != null && !value.isBlank()) {
            throw paramInvalid();
        }
        return null;
    }

    /** 连接器字段校验（W3）：CONNECTOR 必填且格式合法（E1/E3），其余形态禁带（E2 互斥）。 */
    private static String[] validateConnectorFields(final DeliveryType type, final String connectorNo,
            final String sourceNo) {
        final boolean hasConnector = connectorNo != null && !connectorNo.isBlank();
        final boolean hasSource = sourceNo != null && !sourceNo.isBlank();
        if (type == DeliveryType.CONNECTOR) {
            if (!hasConnector || !hasSource) {
                throw paramInvalid();
            }
            final String trimmedConnector = connectorNo.trim();
            final String trimmedSource = sourceNo.trim();
            if (!CONNECTOR_NO.matcher(trimmedConnector).matches()
                    || !SOURCE_NO.matcher(trimmedSource).matches()) {
                throw paramInvalid();
            }
            return new String[] {trimmedConnector, trimmedSource};
        }
        if (hasConnector || hasSource) {
            throw paramInvalid();
        }
        return new String[] {null, null};
    }

    /** 占用登记（W4 / E6）：连接器状态拒绝映射为既有范围不符语义码 + 专属可读文案（不透传内部码）。 */
    private void occupyOrReject(final String connectorNo, final String deliveryNo) {
        try {
            connectorPort.occupy(connectorNo, deliveryNo);
        } catch (final ConnectorInternalException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
                    DeliveryErrorCodes.CONNECTOR_STATE_MESSAGE);
        }
    }

    /** 属主核对 + 内部源读取（W3-⑦⑧ / E5 / E8 / E15）：读失败统一收敛取数失败（finally 由调用方释放）。 */
    private String verifyOwnerAndRead(final String connectorNo, final String sourceNo,
            final String deliveryNo, final String contractNo, final String purpose,
            final ContractSummary summary) {
        final String ownerSubject;
        try {
            ownerSubject = connectorPort.getConnectorOwnerSubjectNo(connectorNo);
        } catch (final ConnectorInternalException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.FETCH_FAILED,
                    fetchFailedMessage(e.reason()), e);
        }
        if (!summary.providerNo().equals(ownerSubject)) {
            throw new DeliveryBizException(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
                    DeliveryErrorCodes.OWNER_MISMATCH_MESSAGE);
        }
        final ConnectorInternalPort.ConnectorSourceRead read;
        try {
            read = connectorPort.readSource(connectorNo, sourceNo, deliveryNo, contractNo, purpose);
        } catch (final ConnectorInternalException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.FETCH_FAILED,
                    fetchFailedMessage(e.reason()), e);
        }
        if (!read.success()) {
            throw new DeliveryBizException(DeliveryErrorCodes.FETCH_FAILED,
                    fetchFailedMessage(read.summary()));
        }
        return read.summary();
    }

    private static String fetchFailedMessage(final String reason) {
        return (reason == null || reason.isBlank())
                ? DeliveryErrorCodes.FETCH_FAILED_MESSAGE
                : DeliveryErrorCodes.FETCH_FAILED_MESSAGE + "：" + reason;
    }

    private static String validateElement(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_ELEMENT_CHARS) {
            throw paramInvalid();
        }
        return value;
    }

    private static void validateForm(final DeliveryType type, final String productType) {
        // WBS-3.6.8 前置检查④落定：CONNECTOR 允许形态与 FILE 同构 {DATASET, REPORT, MODEL}
        final boolean matches = (type == DeliveryType.API && "API".equals(productType))
                || (type == DeliveryType.FILE && FILE_FORMS.contains(productType))
                || (type == DeliveryType.CONNECTOR && FILE_FORMS.contains(productType));
        if (!matches) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
                    DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH_MESSAGE);
        }
    }

    private byte[] readSource(final FileSource source) {
        final int limit = (int) properties.getFile().getMaxBytes() + 1;
        try (var input = Files.newInputStream(source.path(), LinkOption.NOFOLLOW_LINKS)) {
            // 有界读取：清单校验与打开之间文件可能被替换/增长，禁止无界 readAllBytes
            final byte[] data = input.readNBytes(limit);
            if (data.length > properties.getFile().getMaxBytes()) {
                throw new DeliveryBizException(DeliveryErrorCodes.FILE_TOO_LARGE,
                        DeliveryErrorCodes.FILE_TOO_LARGE_MESSAGE);
            }
            return data;
        } catch (final IOException e) {
            log.warn("文件源读取失败: fileId={}", source.fileId());
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                    DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE, e);
        }
    }

    private byte[] encrypt(final byte[] plaintext) {
        try {
            return sm4Service.encrypt(plaintext, properties.getFile().getKeyRef());
        } catch (final RuntimeException e) {
            log.error("交付包加密失败", e);
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, e);
        }
    }

    private static DeliveryBizException paramInvalid() {
        return new DeliveryBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
    }
}
