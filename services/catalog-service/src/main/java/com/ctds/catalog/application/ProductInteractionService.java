package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.ProductFavoriteRepository;
import com.ctds.catalog.domain.ProductInteractionLog;
import com.ctds.catalog.domain.ProductInteractionLogRepository;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProductSubscriptionRepository;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 目录交互服务（WBS-3.3.4 hifi §8；W4 收藏 / W5 取消收藏 / W6 订阅 / W7 退订）。
 *
 * <p>写面链序（hifi §1 定稿，顺序即错误码优先序）：① 认证（guard 401）→ ② 权限点 catalog.interact
 * （controller 静态门 403）→ ③ ADMITTED（仅新发起 W4/W6；1007C0006 统一文案，零副作用不留痕）→
 * ④ 幂等判定（本人 uk 条目已存在 → 重放首次结果，不新增行、不新增留痕——<b>先于状态门槛</b>：
 * 下架前已收藏的产品，下架后重复收藏 = 幂等重放而非"新发起"）→ ⑤ 产品状态门槛（W4/W6：status ≠
 * LISTED → 1007C0011 防枚举同形 + DENIED 留痕）→ ⑥ 写入 + product_interaction_log 留痕
 * SUCCEEDED（同事务，边界在仓储方法内）。W5/W7 无 ADMITTED、无状态门槛（下架产品的既有条目可取消，
 * 行为 6 规则 3 仅限"新发起"）：③' 本人条目存在性 → 无条目 1007C0012 + DENIED 留痕 → 有条目
 * DELETE + 留痕。</p>
 *
 * <p>事务口径（沿 3.3.2 先例）：服务方法<b>无 @Transactional</b>——DENIED 留痕须在业务异常抛出前
 * 已提交（不可被回滚吞掉，行为 7 规则 4 可取证）；成功路径"条目 + 留痕"两写同事务的边界在仓储方法内
 * （{@code JdbcProductFavoriteRepository.insertWithLog} 等沿 {@code JdbcDatasetRepository.create} 先例）。
 * 交互时间统一截断到秒（DATETIME 列默认精度 0，保证首次返回值与幂等重放读回值逐字一致）。</p>
 */
@Service
public class ProductInteractionService {

    private final DataProductRepository dataProductRepository;
    private final ProductFavoriteRepository favoriteRepository;
    private final ProductSubscriptionRepository subscriptionRepository;
    private final ProductInteractionLogRepository interactionLogRepository;
    private final SubjectAdmissionPort admissionPort;
    private final CatalogAccessGuard guard;
    private final Clock clock;

    public ProductInteractionService(final DataProductRepository dataProductRepository,
            final ProductFavoriteRepository favoriteRepository,
            final ProductSubscriptionRepository subscriptionRepository,
            final ProductInteractionLogRepository interactionLogRepository,
            final SubjectAdmissionPort admissionPort, final CatalogAccessGuard guard, final Clock clock) {
        this.dataProductRepository = dataProductRepository;
        this.favoriteRepository = favoriteRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.interactionLogRepository = interactionLogRepository;
        this.admissionPort = admissionPort;
        this.guard = guard;
        this.clock = clock;
    }

    /** W4 收藏（行为 6 规则 1；幂等重放先于状态门槛——链序锚 T3）。返回 favoritedAt（首次时间）。 */
    public LocalDateTime favorite(final long productId) {
        final String subject = guard.requireSubject();
        requireAdmitted(subject);
        final LocalDateTime now = now();
        final Optional<LocalDateTime> existing = favoriteRepository.findFavoritedAt(subject, productId);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireListed(subject, productId, ProductInteractionLog.ACTION_FAVORITE, now);
        favoriteRepository.insertWithLog(subject, productId, now,
                succeededLog(subject, productId, ProductInteractionLog.ACTION_FAVORITE, now));
        return now;
    }

    /** W5 取消收藏（行为 6 规则 1 仅本人条目；无状态门槛）。返回被删条目的首次时间。 */
    public LocalDateTime unfavorite(final long productId) {
        final String subject = guard.requireSubject();
        final LocalDateTime now = now();
        final Optional<LocalDateTime> existing = favoriteRepository.findFavoritedAt(subject, productId);
        if (existing.isEmpty()) {
            insertDeniedLog(subject, productId, ProductInteractionLog.ACTION_UNFAVORITE,
                    CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND, now);
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND,
                    CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND_MESSAGE);
        }
        favoriteRepository.deleteWithLog(subject, productId,
                succeededLog(subject, productId, ProductInteractionLog.ACTION_UNFAVORITE, now));
        return existing.get();
    }

    /** W6 订阅（行为 6 规则 2；链序同 W4）。返回 subscribedAt（首次时间）。 */
    public LocalDateTime subscribe(final long productId) {
        final String subject = guard.requireSubject();
        requireAdmitted(subject);
        final LocalDateTime now = now();
        final Optional<LocalDateTime> existing = subscriptionRepository.findSubscribedAt(subject, productId);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireListed(subject, productId, ProductInteractionLog.ACTION_SUBSCRIBE, now);
        subscriptionRepository.insertWithLog(subject, productId, now,
                succeededLog(subject, productId, ProductInteractionLog.ACTION_SUBSCRIBE, now));
        return now;
    }

    /** W7 退订（链序同 W5）。返回被删条目的首次时间。 */
    public LocalDateTime unsubscribe(final long productId) {
        final String subject = guard.requireSubject();
        final LocalDateTime now = now();
        final Optional<LocalDateTime> existing = subscriptionRepository.findSubscribedAt(subject, productId);
        if (existing.isEmpty()) {
            insertDeniedLog(subject, productId, ProductInteractionLog.ACTION_UNSUBSCRIBE,
                    CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND, now);
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND,
                    CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND_MESSAGE);
        }
        subscriptionRepository.deleteWithLog(subject, productId,
                succeededLog(subject, productId, ProductInteractionLog.ACTION_UNSUBSCRIBE, now));
        return existing.get();
    }

    /** 产品状态门槛（链序 ⑤，仅 W4/W6 新发起）：非在架（含不存在）→ DENIED 留痕（独立提交，不随
     * 异常回滚）+ 1007C0011 防枚举同形（不区分未上架/已下架/已注销/不存在——行为 7 规则 2 同源口径）。 */
    private void requireListed(final String subject, final long productId, final String action,
            final LocalDateTime now) {
        if (dataProductRepository.findStatusById(productId).orElse(null) != ProductStatus.LISTED) {
            insertDeniedLog(subject, productId, action,
                    CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED, now);
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED,
                    CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED_MESSAGE);
        }
    }

    /** ADMITTED 资格门槛（Q8-A，仅 W4/W6）：未入驻统一文案（零留痕零副作用）；服务不可用 → 1007S0001。 */
    private void requireAdmitted(final String subject) {
        final SubjectAdmission admission = admissionPort.check(subject);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.CATALOG_ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new CatalogBizException(CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    /** 当前时间（截断到秒——DATETIME 列默认精度 0，保证首次返回与幂等重放读回一致）。 */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private ProductInteractionLog succeededLog(final String subject, final long productId,
            final String action, final LocalDateTime now) {
        return new ProductInteractionLog(subject, productId, action,
                ProductInteractionLog.OUTCOME_SUCCEEDED, null, now);
    }

    /** DENIED 拒绝留痕（独立提交——无外层事务，异常抛出前已落库可取证，行为 7 规则 4）。 */
    private void insertDeniedLog(final String subject, final long productId, final String action,
            final com.ctds.common.errorcode.ErrorCode reason, final LocalDateTime now) {
        interactionLogRepository.insert(new ProductInteractionLog(subject, productId, action,
                ProductInteractionLog.OUTCOME_DENIED, CatalogErrorCodes.tailOf(reason), now));
    }
}
