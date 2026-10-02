package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.ProductActionLog;
import com.ctds.catalog.domain.ProviderProductRow;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 治理例外读面服务（WBS-3.3.5 hifi §4 R12/R13；行为 7 规则 3"治理例外唯一且留痕"）。
 * 机制复用形态 = DB-29 已交付先例（space 域运营档治理查看留痕）：通用动作码 GOVERNANCE_VIEW +
 * 实际登录主体（"谁"真实可追，DB-29 Q3-A）+ 仅运营方访问时写留痕（Q4-A——例外动作本身留痕）；
 * 资源侧落 dataset_action_log、产品侧落 product_action_log（各自域表，零新表——Q5-A）。
 * 权限点 catalog.governance 由 controller 静态门承载（非运营方 403，不写留痕——DB-29 Q4-A 负向锚）。
 * 读面拒绝（对象不存在）不留痕（DB-37 落定口径：拒绝留痕挂写面，读面拒绝零留痕零副作用）。
 */
@Service
public class ProductGovernanceService {

    private final DataProductRepository productRepository;
    private final DatasetRepository datasetRepository;
    private final CatalogAccessGuard guard;
    private final Clock clock;

    public ProductGovernanceService(final DataProductRepository productRepository,
            final DatasetRepository datasetRepository, final CatalogAccessGuard guard,
            final Clock clock) {
        this.productRepository = productRepository;
        this.datasetRepository = datasetRepository;
        this.guard = guard;
        this.clock = clock;
    }

    /** R12 产品治理详情（任意状态，含未上架/已下架/已注销——C-3.2 剧本 S3-3 判定面）+ 查看留痕。 */
    public ProviderProductRow product(final long productId) {
        final String subject = guard.requireSubject();
        final ProviderProductRow product = productRepository.findById(productId)
                .orElseThrow(() -> new CatalogBizException(CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND,
                        CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND_MESSAGE));
        productRepository.insertLog(new ProductActionLog(null, product.productId(),
                ProductActionLog.ACTION_GOVERNANCE_VIEW, subject, null, LocalDateTime.now(clock)));
        return product;
    }

    /** R13 资源治理详情（含申报字段与已注销对象——C-3.2 剧本 S3-3"全部产品与资源"）+ 查看留痕
     * （dataset_action_log + GOVERNANCE_VIEW，沿 DB-29 同款值域登记）。 */
    public Dataset dataset(final long datasetId) {
        final String subject = guard.requireSubject();
        final Dataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new CatalogBizException(
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS,
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE));
        datasetRepository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(),
                dataset.id(), ProductActionLog.ACTION_GOVERNANCE_VIEW, null, null,
                com.ctds.catalog.domain.ActionResult.SUCCESS, null, LocalDateTime.now(clock)));
        return dataset;
    }
}
