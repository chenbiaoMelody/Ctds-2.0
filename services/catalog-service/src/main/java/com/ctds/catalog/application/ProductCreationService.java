package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.ProductActionLog;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProviderProductRow;
import com.ctds.common.idempotency.Idempotent;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 产品封装服务（幂等边界；WBS-3.3.5 hifi §4 W8 步骤 ⑩）。独立于 {@link ProductCommandService} 的
 * 原因沿 {@code DatasetRegistrationService} 先例：幂等键经注解切面拦截，跨 Bean 调用使切面经代理
 * 生效（同类自调用会被 AOP 绕过）。
 *
 * <p>幂等键 = 提供方 + 来源资源 + 归一化产品名（同请求语义 = 同主体从同资源封装同名产品，hifi §1
 * W8 幂等头承载其余要素差异——同键不同要素 = 首次结果重放，ADR-007 模式 B）；幂等命中后重读当前行，
 * 已注销 → 1007C0017（沿 dataset 登记幂等命中后置判定同款：不以 200 返回已注销对象的重放结果）。</p>
 */
@Service
public class ProductCreationService {

    private final DataProductRepository productRepository;
    private final Clock clock;

    public ProductCreationService(final DataProductRepository productRepository, final Clock clock) {
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /**
     * 封装落库（W8 校验链已由命令服务完成——本方法只承载幂等切面与两写事务）：INSERT
     * （status=DRAFT）+ CREATE 留痕同事务（仓储方法内，沿 {@code insertWithLog} 先例）；
     * uk_provider_norm_name 并发兜底 DuplicateKeyException → 1007C0017（仓储侧转译）。
     */
    @Idempotent(key = "'PRODUCT:' + #cmd.providerSubjectNo + ':' + #cmd.datasetId + ':"
            + "' + #cmd.normalizedName")
    public ProviderProductRow create(final CreateProductCommand cmd) {
        // 判重预检在幂等切面之内（沿 dataset register 同款链位——重放命中不执行本方法体，
        // 预检不拦截重放）：归一化同名（唯一键含已注销行）→ 1007C0017
        if (productRepository.existsByProviderAndNormalizedName(cmd.providerSubjectNo(),
                cmd.normalizedName())) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_DUPLICATED,
                    CatalogErrorCodes.PRODUCT_NAME_DUPLICATED_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final ProviderProductRow row = new ProviderProductRow(null, cmd.productName(), cmd.intro(),
                cmd.productType(), cmd.pricingModel(), cmd.priceAmount(), ProductStatus.DRAFT,
                cmd.providerSubjectNo(), cmd.datasetId(), cmd.categoryCode(), null, null, now);
        final long id = productRepository.create(row,
                new ProductActionLog(null, 0L, ProductActionLog.ACTION_CREATE,
                        cmd.providerSubjectNo(), "产品封装（初始未上架）", now));
        final ProviderProductRow created = productRepository.findById(id).orElseThrow();
        // 幂等命中后置判定（沿 dataset 模式 B 同款）：命中返回值是首次封装快照，若该产品其后已注销
        // （唯一键含已注销行），按名称唯一口径拒绝重放——不以 200 返回已注销产品的封装结果
        if (created.status() == ProductStatus.CANCELLED) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_DUPLICATED,
                    CatalogErrorCodes.PRODUCT_NAME_DUPLICATED_MESSAGE);
        }
        return created;
    }

    /** 封装命令载荷（W8 校验链产出；normalizedName 已由命令服务归一化）。 */
    public record CreateProductCommand(String providerSubjectNo, long datasetId, String productName,
            String normalizedName, String intro, String productType, String pricingModel,
            java.math.BigDecimal priceAmount, String categoryCode) {
    }
}
