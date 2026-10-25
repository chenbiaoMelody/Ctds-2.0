package com.ctds.delivery.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.audit.DeliveryAuditActions;
import com.ctds.delivery.audit.DeliveryAuditService;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.DeliveryType;
import com.ctds.delivery.infrastructure.FilePackageStore;
import com.ctds.delivery.interfaces.dto.DeliveryViews.DeliveryView;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 交付记录读面与加密包领取（hifi §3/§7）：参与者读面、治理例外读面、需求方专属下载。
 * 非参与方/不存在同码同文 1009C0007；提供方下载 1009C0009；一切判定在服务端。
 */
@Service
public class DeliveryQueryService {

    private final DeliveryRepository repository;
    private final FilePackageStore packageStore;
    private final DeliveryAuditService audit;

    public DeliveryQueryService(final DeliveryRepository repository, final FilePackageStore packageStore,
            final DeliveryAuditService audit) {
        this.repository = repository;
        this.packageStore = packageStore;
        this.audit = audit;
    }

    /** 参与者详情（需求方/提供方）。 */
    public DeliveryView get(final String deliveryNo, final String callerNo) {
        final Delivery delivery = visibleRecord(deliveryNo, callerNo);
        audit.success(DeliveryAuditActions.READ, delivery.deliveryNo(), delivery.contractNo(), callerNo);
        return DeliveryView.from(delivery);
    }

    /** 参与者列表（按调用者过滤；非参与方得到空列表，不泄露记录存在性）。 */
    public PageResult<DeliveryView> list(final String callerNo, final String contractNo,
            final String deliveryType, final PageQuery page) {
        final DeliveryType type = parseType(deliveryType);
        final List<DeliveryView> list = repository
                .listForParticipant(callerNo, contractNo, type, page).stream()
                .map(DeliveryView::from).toList();
        final long total = repository.countForParticipant(callerNo, contractNo, type);
        audit.success(DeliveryAuditActions.READ, null, contractNo, callerNo);
        return PageResult.of(list, total, page);
    }

    /** 加密包领取（仅需求方；提供方/其他参与方不返回数据）。 */
    public PackageDownload download(final String deliveryNo, final String callerNo) {
        final Delivery delivery = repository.findByDeliveryNo(deliveryNo).orElseThrow(() -> {
            audit.denied(DeliveryAuditActions.PACKAGE_DOWNLOAD, deliveryNo, null, callerNo,
                    DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value());
            return notVisible();
        });
        if (!callerNo.equals(delivery.requesterNo())) {
            final boolean participant = callerNo.equals(delivery.providerNo());
            final DeliveryBizException ex = participant ? notAllowedPackage() : notVisible();
            audit.denied(DeliveryAuditActions.PACKAGE_DOWNLOAD, deliveryNo, delivery.contractNo(),
                    callerNo, ex.getErrorCode().value(), delivery.deliveryType().name());
            throw ex;
        }
        // 加密包领取仅限带包形态：FILE（3.5.3）与 SANDBOX_EXPORT（3.5.5 审批后导出，hifi §5
        // 复用下载端点）；API 行与缺包行仍拒绝，既有语义零变更
        if ((delivery.deliveryType() != DeliveryType.FILE
                && delivery.deliveryType() != DeliveryType.SANDBOX_EXPORT)
                || delivery.packageId() == null) {
            audit.denied(DeliveryAuditActions.PACKAGE_DOWNLOAD, deliveryNo, delivery.contractNo(),
                    callerNo, DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH.value(),
                    delivery.deliveryType().name());
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
                    DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH_MESSAGE);
        }
        final Path path;
        try {
            path = packageStore.requirePath(delivery.packageId());
        } catch (final DeliveryBizException e) {
            audit.failure(DeliveryAuditActions.PACKAGE_DOWNLOAD, deliveryNo, delivery.contractNo(),
                    callerNo, e.getErrorCode().value(), delivery.deliveryType().name());
            throw e;
        }
        audit.success(DeliveryAuditActions.PACKAGE_DOWNLOAD, deliveryNo, delivery.contractNo(), callerNo);
        return new PackageDownload(path, delivery.fileName(), delivery.checksum());
    }

    /** 治理详情（运营方；读即留痕）。 */
    public DeliveryView governanceGet(final String deliveryNo, final String operatorNo) {
        final Delivery delivery = repository.findByDeliveryNo(deliveryNo).orElseThrow(() -> {
            audit.denied(DeliveryAuditActions.DENIED, deliveryNo, null, operatorNo,
                    DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value());
            return notVisible();
        });
        audit.success(DeliveryAuditActions.GOVERNANCE_READ, delivery.deliveryNo(),
                delivery.contractNo(), operatorNo);
        return DeliveryView.from(delivery);
    }

    /** 治理列表（运营方；读即留痕）。 */
    public PageResult<DeliveryView> governanceList(final String contractNo, final String deliveryNo,
            final PageQuery page, final String operatorNo) {
        final List<DeliveryView> list = repository.listGovernance(contractNo, deliveryNo, page).stream()
                .map(DeliveryView::from).toList();
        final long total = repository.countGovernance(contractNo, deliveryNo);
        audit.success(DeliveryAuditActions.GOVERNANCE_READ, deliveryNo, contractNo, operatorNo);
        return PageResult.of(list, total, page);
    }

    private Delivery visibleRecord(final String deliveryNo, final String callerNo) {
        final Delivery delivery = repository.findByDeliveryNo(deliveryNo).orElseThrow(() -> {
            audit.denied(DeliveryAuditActions.DENIED, deliveryNo, null, callerNo,
                    DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value());
            return notVisible();
        });
        if (!callerNo.equals(delivery.requesterNo()) && !callerNo.equals(delivery.providerNo())) {
            audit.denied(DeliveryAuditActions.DENIED, deliveryNo, delivery.contractNo(), callerNo,
                    DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value(), delivery.deliveryType().name());
            throw notVisible();
        }
        return delivery;
    }

    private static DeliveryType parseType(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryType.valueOf(value.trim());
        } catch (final IllegalArgumentException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                    DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
        }
    }

    private static DeliveryBizException notVisible() {
        return new DeliveryBizException(DeliveryErrorCodes.DELIVERY_NOT_VISIBLE,
                DeliveryErrorCodes.DELIVERY_NOT_VISIBLE_MESSAGE);
    }

    private static DeliveryBizException notAllowedPackage() {
        return new DeliveryBizException(DeliveryErrorCodes.PACKAGE_NOT_ALLOWED,
                DeliveryErrorCodes.PACKAGE_NOT_ALLOWED_MESSAGE);
    }

    /** 领取结果（加密包路径 + 展示名 + 明文 SM3）。 */
    public record PackageDownload(Path path, String fileName, String checksum) {
    }
}
