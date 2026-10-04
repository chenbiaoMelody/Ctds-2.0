package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.InteractionLogRow;
import com.ctds.catalog.domain.ProductActionLogRepository;
import com.ctds.catalog.domain.ProductActionLogRow;
import com.ctds.catalog.domain.ProductChangeLogRow;
import com.ctds.catalog.domain.ProductFavoriteRepository;
import com.ctds.catalog.domain.ProductInteractionLogRepository;
import com.ctds.catalog.domain.ProviderProductRow;
import com.ctds.catalog.domain.ProductSubscriptionRepository;
import com.ctds.common.auth.AccessControl;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 统一目录查询服务（WBS-3.3.4 hifi §8；R6 检索 / R7 详情 / R8 变更留痕 / R9 我的收藏 / R10 我的订阅）。
 * 链序：认证（guard 401）→ 权限点（controller 静态门 403）→ ADMITTED（R6/R7/R8，1007C0006 统一文案
 * 零副作用）→ 参数校验 / 存在性判定。R5 类目树在 {@link CategoryTreeService}（无 ADMITTED 门槛，
 * 平台公开字典）。
 */
@Service
public class ProductCatalogQueryService {

    /** keyword 长度上限（hifi §1：超长 1007C0013；不与 DatasetNameNormalizer 混用——模糊匹配非精确判重）。 */
    private static final int KEYWORD_MAX_LENGTH = 64;

    /** 治理例外权限点（WBS-3.3.6 R15：admin 读留痕不另写留痕——hifi §1.2 读面留痕口径）。 */
    private static final String PERMISSION_GOVERNANCE = "catalog.governance";

    private final DataProductRepository dataProductRepository;
    private final ProductFavoriteRepository favoriteRepository;
    private final ProductSubscriptionRepository subscriptionRepository;
    private final ProductActionLogRepository actionLogRepository;
    private final ProductInteractionLogRepository interactionLogRepository;
    private final CategoryPort categoryPort;
    private final CatalogAccessGuard guard;
    private final AccessControl accessControl;

    public ProductCatalogQueryService(final DataProductRepository dataProductRepository,
            final ProductFavoriteRepository favoriteRepository,
            final ProductSubscriptionRepository subscriptionRepository,
            final ProductActionLogRepository actionLogRepository,
            final ProductInteractionLogRepository interactionLogRepository,
            final CategoryPort categoryPort,
            final CatalogAccessGuard guard, final AccessControl accessControl) {
        this.dataProductRepository = dataProductRepository;
        this.favoriteRepository = favoriteRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.actionLogRepository = actionLogRepository;
        this.interactionLogRepository = interactionLogRepository;
        this.categoryPort = categoryPort;
        this.guard = guard;
        this.accessControl = accessControl;
    }

    /**
     * R6 目录检索（行为 5）：呈现范围恒仅已上架（仓储 LISTED 硬过滤）→ 分类过滤（categoryCode 须为
     * 类目树内节点，含子树展开）→ keyword（名称/简介两字段 LIKE，≤64 字符）→ 分页。
     * 资格拒绝零副作用（无留痕无写入，沿"资格探针零单据"先例）。
     */
    public PageResult<CatalogProductRow> search(final String categoryCode, final String keyword,
            final PageQuery page) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        if (keyword != null && keyword.length() > KEYWORD_MAX_LENGTH) {
            throw new CatalogBizException(CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID,
                    CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID_MESSAGE);
        }
        List<String> subtreeCodes = null;
        if (categoryCode != null && !categoryCode.isBlank()) {
            if (!categoryPort.existsByCode(categoryCode)) {
                throw new CatalogBizException(CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID,
                        CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID_MESSAGE);
            }
            subtreeCodes = categoryPort.selfAndDescendantCodes(categoryCode);
        }
        return dataProductRepository.searchListed(subtreeCodes, keyword, page);
    }

    /**
     * R7 产品详情（行为 5 规则 1 + 行为 7 规则 2）：仅已上架行；未上架/已下架/已注销/不存在一律
     * 1007C0011 同形拒绝（防枚举——不区分差异）。
     */
    public CatalogProductRow detail(final long productId) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        return dataProductRepository.findListedDetail(productId)
                .orElseThrow(() -> new CatalogBizException(CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED,
                        CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED_MESSAGE));
    }

    /**
     * R8 产品变更留痕（行为 6 规则 2，限本人订阅者）：产品行不存在 → 1007C0011；非订阅者 →
     * 1007C0011 同码同文案（不暴露订阅关系与产品存在性，防枚举）；变更感知 = 留痕可查（Q6-A，
     * 写入归 3.3.5，空表天然空页）。
     */
    public PageResult<ProductChangeLogRow> changeLogs(final long productId, final PageQuery page) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        if (!dataProductRepository.existsById(productId)
                || subscriptionRepository.findSubscribedAt(subject, productId).isEmpty()) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED,
                    CatalogErrorCodes.PRODUCT_NOT_FOUND_OR_NOT_LISTED_MESSAGE);
        }
        return actionLogRepository.pageByProduct(productId, page);
    }

    /**
     * R15 产品操作留痕分页（WBS-3.3.6 hifi §1.2，<b>全值域</b>）：读面 = 提供方本人 或 治理例外
     * （{@code catalog.governance}，admin 读不另写留痕）；非本人且非治理档与不存在 → 1007C0012
     * 管理面文案同形拒绝（404；沿产品面读面既有口径，不写留痕）。
     */
    public PageResult<ProductActionLogRow> actionLogs(final long productId, final PageQuery page) {
        final String subject = guard.requireSubject();
        final ProviderProductRow product = dataProductRepository.findById(productId)
                .orElseThrow(ProductCatalogQueryService::manageNotFound);
        if (!product.providerSubjectNo().equals(subject)
                && !accessControl.hasPermission(PERMISSION_GOVERNANCE)) {
            throw manageNotFound();
        }
        return actionLogRepository.pageAllByProduct(productId, page);
    }

    /** R16 本人互动留痕分页（WBS-3.3.6 hifi §1.2；恒仅本人、无跨主体读法；空列表正常空页）。 */
    public PageResult<InteractionLogRow> myInteractionLogs(final PageQuery page) {
        return interactionLogRepository.pageBySubject(guard.requireSubject(), page);
    }

    /** 产品不存在/无权操作（统一 1007C0012 管理面文案，读面同形）。 */
    private static CatalogBizException manageNotFound() {
        return new CatalogBizException(CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND,
                CatalogErrorCodes.PRODUCT_MANAGE_NOT_FOUND_MESSAGE);
    }

    /** R9 我的收藏分页（恒仅本人条目；无 ADMITTED 门槛——条目产生于 ADMITTED 期，hifi §1）。 */
    public PageResult<CatalogProductRow> favorites(final PageQuery page) {
        return favoriteRepository.pageBySubject(guard.requireSubject(), page);
    }

    /** R10 我的订阅分页（口径同 R9）。 */
    public PageResult<CatalogProductRow> subscriptions(final PageQuery page) {
        return subscriptionRepository.pageBySubject(guard.requireSubject(), page);
    }
}
