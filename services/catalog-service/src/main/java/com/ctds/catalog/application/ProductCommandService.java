package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.PricingModel;
import com.ctds.catalog.domain.ProductActionLog;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProviderProductRow;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 产品命令服务（WBS-3.3.5 hifi §4：W8 封装校验链 / W9 变更 / W10 上架 / W11 下架 / W12 强制下架 /
 * W13 注销 + R11 提供方管理列表）。W8 落库经 {@link ProductCreationService} 幂等边界（跨 Bean 调用沿
 * {@code DatasetRegistrationService} 先例——同类自调用会被 AOP 绕过）。
 *
 * <p>校验链顺序即错误码优先序（hifi §4 + §11 勘误：终态/状态机断言先于定价与要素校验，沿 dataset
 * requireActive 同款先例；W9 空载体 400 先于存在性查询——省一次查询，§11 勘误登记）。
 * 写面越权一律 DENIED 留痕（行为 4 规则 6/行为 7 规则 4，product_action_log——产品已成行；
 * 封装越权落资源域 dataset_action_log）；W9/W11/W13 的 ADMITTED 现值复核为已确认设计（hifi §4）
 * 的从紧选择——规格行为 4 规则 2 仅上架明文要求，见 hifi §11 勘误。服务方法无 @Transactional：
 * DENIED 留痕独立提交不被回滚吞掉（沿 ProductInteractionService 先例），成功路径"行 + 留痕"
 * 两写同事务边界在仓储方法内。</p>
 */
@Service
public class ProductCommandService {

    /** 产品名称长度门槛（product_name 列 VARCHAR(128)，hifi §3.1）。 */
    private static final int NAME_MAX_LENGTH = 128;
    /** 简介长度上限（intro 列 VARCHAR(512)）。 */
    private static final int INTRO_MAX_LENGTH = 512;
    /** 强制下架理由长度上限（summary 列 VARCHAR(512) 内承载）。 */
    private static final int FORCE_REASON_MAX_LENGTH = 256;
    /** 留痕摘要单值截断长度（SEC1 修复：from/to 值各截断到 64 字符，保证 summary 恒 ≤512）。 */
    private static final int SUMMARY_VALUE_MAX_LENGTH = 64;
    /** 分成比例上限（Q2-A：REVENUE_SHARE 档 price_amount = 百分比 0~100）。 */
    private static final BigDecimal SHARE_RATE_MAX = new BigDecimal("100");
    /** 免费档枚举名（price_amount 恒 NULL——Q2-A）。 */
    private static final String FREE_MODEL = PricingModel.FREE.name();

    private final DataProductRepository productRepository;
    private final DatasetRepository datasetRepository;
    private final SpaceMembershipPort spaceMembershipPort;
    private final com.ctds.catalog.domain.CategoryPort categoryPort;
    private final ProductCreationService productCreationService;
    private final CatalogAccessGuard guard;
    private final Clock clock;

    public ProductCommandService(final DataProductRepository productRepository,
            final DatasetRepository datasetRepository, final SpaceMembershipPort spaceMembershipPort,
            final com.ctds.catalog.domain.CategoryPort categoryPort,
            final ProductCreationService productCreationService, final CatalogAccessGuard guard,
            final Clock clock) {
        this.productRepository = productRepository;
        this.datasetRepository = datasetRepository;
        this.spaceMembershipPort = spaceMembershipPort;
        this.categoryPort = categoryPort;
        this.productCreationService = productCreationService;
        this.guard = guard;
        this.clock = clock;
    }

    // ==== R11 提供方管理列表 ====

    /** R11：本人全状态产品分页（管理视图，含未上架/已下架/已注销——行为 7 规则 1 本人读取）。 */
    public com.ctds.common.pagination.PageResult<ProviderProductRow> mine(
            final com.ctds.common.pagination.PageQuery page) {
        return productRepository.pageByProvider(guard.requireSubject(), page);
    }

    // ==== W8 封装（行为 3；校验链 + 归一化后交幂等边界）====

    /**
     * 封装入口（hifi §4 W8 校验链，顺序即错误码优先序）：①认证+权限点（controller 静态门）→
     * ②ADMITTED → ③载体校验（名称 1007C0018/简介必填 400/枚举 400）→ ④资源存在且本人
     * （1007C0005/0006 复用 + DENIED_CREATE 资源域留痕）→ ⑤资源 ACTIVE 且空间未解散
     * （1007C0016）→ ⑥类目（显式校验 1007C0014/缺省继承）→ ⑦定价组合（1007C0020/400）→
     * ⑨幂等边界（判重预检在其内——{@link ProductCreationService}，跨 Bean）。
     */
    public ProviderProductRow create(final CreateProductCommand request) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        final String productName = validProductName(request.productName());
        final String normalizedName = com.ctds.catalog.domain.DatasetNameNormalizer
                .normalize(productName);
        if (normalizedName.isEmpty() || normalizedName.length() > NAME_MAX_LENGTH) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_INVALID,
                    CatalogErrorCodes.PRODUCT_NAME_INVALID_MESSAGE);
        }
        final String intro = requireIntro(request.intro());
        final String type = validType(request.productType(), true);
        final String model = requireModel(request.pricingModel());
        validPricingAmount(model, request.priceAmount());
        final Dataset dataset = datasetRepository.findById(request.datasetId())
                .orElseThrow(() -> new CatalogBizException(
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS,
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE));
        if (!dataset.ownerSubjectNo().equals(subject)) {
            // 封装前提 = 资源登记主体本人（行为 3 规则 1）；越权属资源域动作 → DENIED_CREATE 留痕落
            // dataset_action_log（沿 dataset requireOwner 模式），码复用 1007C0006（hifi §2）
            datasetRepository.insertLog(new com.ctds.catalog.domain.DatasetActionLog(null, subject,
                    dataset.spaceId(), dataset.id(),
                    ProductActionLog.deniedActionOf(ProductActionLog.ACTION_CREATE),
                    dataset.status().name(), null, com.ctds.catalog.domain.ActionResult.DENIED,
                    CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_FORBIDDEN),
                    LocalDateTime.now(clock)));
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.DATASET_FORBIDDEN_MESSAGE);
        }
        requireDatasetUsable(dataset.id(), subject);
        final String categoryCode = resolveCategory(request.categoryCode(), dataset);
        // 判重预检在幂等切面之内（ProductCreationService，沿 dataset register 同款链位）——
        // 命令服务侧不预检，否则幂等重放会被 1007C0017 挡住（重放必须先于判重命中）
        return productCreationService.create(new ProductCreationService.CreateProductCommand(subject,
                dataset.id(), productName, normalizedName, intro, type, model, request.priceAmount(),
                categoryCode));
    }

    /** W8 请求载荷（hifi §1：六要素齐备 + 类目可选；接口层 CreateProductRequest.toCommand() 产出）。 */
    public record CreateProductCommand(long datasetId, String productName, String intro,
            String productType, String pricingModel, java.math.BigDecimal priceAmount,
            String categoryCode) {
    }

    /** 变更请求载荷（null = 不变更该项；名称不可变更——hifi §1 W9；接口层同款产出）。 */
    public record ProductUpdateCommand(String intro, String productType, String pricingModel,
            BigDecimal priceAmount, String categoryCode) {
    }

    /** 产品名称校验（W8 载体链，沿 1007C0018 口径：空/超长/归一化后为空一律拒绝）。 */
    private static String validProductName(final String productName) {
        if (productName == null || productName.isBlank()) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_INVALID, "产品名称不能为空");
        }
        if (productName.length() > NAME_MAX_LENGTH) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_INVALID,
                    "产品名称超长（≤" + NAME_MAX_LENGTH + " 字符）");
        }
        return productName;
    }

    /** 定价档位校验（W8：必填且四档受控枚举——"免费也须显式选择"，行为 3 规则 3；缺失/非法 → 400）。 */
    private static String requireModel(final String pricingModel) {
        if (pricingModel == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "定价模型不能为空（免费档也须显式选择）");
        }
        return validPricingModel(pricingModel);
    }

    // ==== W9 变更（行为 4 规则 5）====

    /**
     * 变更：仅提供方本人（1007C0015 + DENIED_UPDATE 留痕）；ADMITTED 现值复核（hifi §4 定稿）；
     * 终态拒绝（1007C0019）；可变字段集 = 简介/形态/定价模型/定价数值/类目（名称不可变——沿
     * dataset 名称不可变先例，防"改头换面"绕过命名唯一）；逐字段 from→to 摘要入 UPDATE 留痕
     * （单值截断 ≤64——SEC1 修复）。
     */
    public ProviderProductRow update(final long productId, final ProductUpdateCommand request) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        if (request.intro() == null && request.productType() == null && request.pricingModel() == null
                && request.priceAmount() == null && request.categoryCode() == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID,
                    "至少提供一项可变更字段（简介/形态/定价模型/定价数值/类目）");
        }
        final ProviderProductRow product = load(productId);
        requireProvider(product, subject, ProductActionLog.deniedActionOf(ProductActionLog.ACTION_UPDATE));
        requireNotCancelled(product);
        final PricingUpdate pricing = validPricing(request.pricingModel(), request.priceAmount(),
                product);
        final String intro = validIntro(request.intro());
        final String type = validType(request.productType(), false);
        final String category = validCategory(request.categoryCode());
        final LocalDateTime now = LocalDateTime.now(clock);
        final StringBuilder summary = new StringBuilder();
        appendChange(summary, "intro", product.intro(), intro);
        appendChange(summary, "productType", product.productType(), type);
        if (request.pricingModel() != null) {
            appendChange(summary, "pricingModel", product.pricingModel(), pricing.modelName());
        }
        if (request.priceAmount() != null || (request.pricingModel() != null
                && FREE_MODEL.equals(pricing.modelName()))) {
            // FREE 切档清空 price_amount 时 DB 变更须留痕（S7-②：to 为 null 以"（清空）"承载）；
            // FREE 原档重发（原值/新值均 null）无实际 DB 变更 → 不产生幻影留痕（复审 R1）
            if (!(product.priceAmount() == null && pricing.amount() == null)) {
                appendChange(summary, "priceAmount", product.priceAmount() == null ? null
                        : product.priceAmount().toPlainString(), pricing.amount() == null ? "（清空）"
                        : pricing.amount().toPlainString());
            }
        }
        appendChange(summary, "categoryCode", product.categoryCode(), category);
        if (summary.isEmpty()) {
            return product;
        }
        productRepository.updateFields(productId, new DataProductRepository.ProductUpdate(
                intro, type, pricing.modelName(), pricing.amount(), category),
                new ProductActionLog(null, productId, ProductActionLog.ACTION_UPDATE, subject,
                        summary.toString(), now));
        return load(productId);
    }

    // ==== W10 上架（行为 4 规则 1/2）====

    /**
     * 上架：仅提供方本人（1007C0015 + DENIED_PUBLISH 留痕）→ 状态机断言（仅未上架/已下架 →
     * 已上架；已注销/重复上架一律 1007C0019）→ 定价齐备（付费档数值缺失 → 1007C0020，C-3.3 剧本
     * S2-2 判定面）→ 来源资源与空间态（已注销/空间已解散 → 1007C0016，剧本 S1-5/S2-4 判定面）→
     * 提供方资格现值复核（1007C0006 同族统一文案）→ 乐观状态转换 + PUBLISH 留痕（并发漂移 →
     * 1007C0019）。
     */
    public ProviderProductRow publish(final long productId) {
        final String subject = guard.requireSubject();
        final ProviderProductRow product = load(productId);
        requireProvider(product, subject, ProductActionLog.deniedActionOf(ProductActionLog.ACTION_PUBLISH));
        requireTransitionable(product, ProductStatus.LISTED);
        requirePricingComplete(product);
        requireDatasetUsable(product.datasetId(), subject);
        guard.requireAdmitted(subject);
        transition(product, ProductStatus.LISTED, LocalDateTime.now(clock),
                ProductActionLog.ACTION_PUBLISH, "产品已上架");
        return load(productId);
    }

    // ==== W11 下架（行为 4 规则 3）====

    /** 下架：仅提供方本人（1007C0015 + DENIED_DELIST 留痕）+ ADMITTED 现值复核（hifi §4 定稿）；
     * 仅在架可下架（1007C0019）。 */
    public ProviderProductRow delist(final long productId) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        final ProviderProductRow product = load(productId);
        requireProvider(product, subject, ProductActionLog.deniedActionOf(ProductActionLog.ACTION_DELIST));
        requireTransitionable(product, ProductStatus.DELISTED);
        transition(product, ProductStatus.DELISTED, null, ProductActionLog.ACTION_DELIST, "产品已下架");
        return load(productId);
    }

    // ==== W12 强制下架（行为 4 规则 4 治理兜底）====

    /**
     * 强制下架（平台运营方，权限点 catalog.governance 静态门）：理由必填（400）；
     * 仅在架可下架（1007C0019）；留痕 summary 恒含理由全文（操作者在 operator 列——行为 4 规则 4）。
     */
    public ProviderProductRow forceDelist(final long productId, final String forceReason) {
        final String subject = guard.requireSubject();
        final ProviderProductRow product = load(productId);
        if (forceReason == null || forceReason.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "强制下架理由不能为空");
        }
        if (forceReason.length() > FORCE_REASON_MAX_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID,
                    "强制下架理由超长（≤" + FORCE_REASON_MAX_LENGTH + " 字符）");
        }
        requireTransitionable(product, ProductStatus.DELISTED);
        transition(product, ProductStatus.DELISTED, null, ProductActionLog.ACTION_FORCE_DELIST,
                "强制下架：" + forceReason);
        return load(productId);
    }

    // ==== W13 注销（行为 4 规则 1：二次确认、不可逆、仅从未上架/已下架发起）====

    /** 注销：仅提供方本人（1007C0015 + DENIED_CANCEL 留痕）+ ADMITTED 现值复核（hifi §4 定稿）；
     * 二次确认必填（沿 W3 先例）；在架注销拒绝（1007C0019，C-3.3 剧本 S3-6 判定面——须先下架）。 */
    public ProviderProductRow cancel(final long productId, final Boolean confirmCancellation) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        final ProviderProductRow product = load(productId);
        requireProvider(product, subject, ProductActionLog.deniedActionOf(ProductActionLog.ACTION_CANCEL));
        if (!Boolean.TRUE.equals(confirmCancellation)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "缺少注销二次确认（confirmCancellation 必须为 true）");
        }
        requireTransitionable(product, ProductStatus.CANCELLED);
        transition(product, ProductStatus.CANCELLED, null, ProductActionLog.ACTION_CANCEL,
                "产品已注销（不可逆）");
        return load(productId);
    }

    // ==== 内部：门槛与校验 ====

    private ProviderProductRow load(final long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new CatalogBizException(CatalogErrorCodes.PRODUCT_RECORD_NOT_FOUND,
                        CatalogErrorCodes.PRODUCT_MANAGE_NOT_FOUND_MESSAGE));
    }

    /** 属主门槛（行为 4 规则 6）：非提供方本人 → DENIED 留痕 + 1007C0015。 */
    private void requireProvider(final ProviderProductRow product, final String subject,
            final String denyAction) {
        if (!product.providerSubjectNo().equals(subject)) {
            productRepository.insertLog(new ProductActionLog(null, product.productId(), denyAction,
                    subject, null, LocalDateTime.now(clock)));
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_FORBIDDEN,
                    CatalogErrorCodes.PRODUCT_FORBIDDEN_MESSAGE);
        }
    }

    /** 终态门槛（行为 4 规则 1）：已注销产品一切动作 → 1007C0019。 */
    private void requireNotCancelled(final ProviderProductRow product) {
        if (product.status() == ProductStatus.CANCELLED) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN,
                    CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN_MESSAGE);
        }
    }

    /** 状态机断言（hifi §4 矩阵）：from 状态须允许转换到目标态，否则 1007C0019。 */
    private void requireTransitionable(final ProviderProductRow product, final ProductStatus to) {
        final boolean allowed = switch (to) {
            case LISTED -> product.status() == ProductStatus.DRAFT
                    || product.status() == ProductStatus.DELISTED;
            case DELISTED -> product.status() == ProductStatus.LISTED;
            case CANCELLED -> product.status() == ProductStatus.DRAFT
                    || product.status() == ProductStatus.DELISTED;
            case DRAFT -> false;
        };
        if (!allowed) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN,
                    CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN_MESSAGE);
        }
    }

    /** 定价齐备门槛（W10 上架前提，C-3.3 剧本 S2-2 判定面）：付费档数值缺失 → 1007C0020。 */
    private void requirePricingComplete(final ProviderProductRow product) {
        if (!FREE_MODEL.equals(product.pricingModel()) && product.priceAmount() == null) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE,
                    CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE_MESSAGE);
        }
    }

    /**
     * 来源资源与空间态门槛（W8 封装前提与 W10 上架前提共用——C6 合并单份，行为 3 规则 1/行为 4
     * 规则 2 同源）：资源已注销/空间已解散 → 1007C0016；空间服务不可达 → 1007S0002；
     * 口径 = 仅 DISSOLVED 阻断（规格"未解散"≠"须 ACTIVE"，isSpaceActive 会误拒 FROZEN 空间——
     * hifi §11 勘误登记；NONE〔空间不存在〕随放行，由资源行存在性兜底）。
     */
    private void requireDatasetUsable(final long datasetId, final String subject) {
        final Dataset dataset = datasetRepository.findById(datasetId)
                .filter(d -> d.status() == DatasetStatus.ACTIVE)
                .orElseThrow(() -> new CatalogBizException(
                        CatalogErrorCodes.PRODUCT_DATASET_STATE_FORBIDDEN,
                        CatalogErrorCodes.PRODUCT_DATASET_STATE_FORBIDDEN_MESSAGE));
        final SpaceMembership membership = spaceMembershipPort.check(dataset.spaceId(), subject);
        if (!membership.available()) {
            throw new CatalogBizException(CatalogErrorCodes.SPACE_SERVICE_UNAVAILABLE,
                    CatalogErrorCodes.SPACE_SERVICE_UNAVAILABLE_MESSAGE);
        }
        if (SpaceMembership.SPACE_STATUS_DISSOLVED.equals(membership.spaceStatus())) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_DATASET_STATE_FORBIDDEN,
                    CatalogErrorCodes.PRODUCT_DATASET_STATE_FORBIDDEN_MESSAGE);
        }
    }

    /**
     * 定价组合校验（W9，Q2-A；W8 侧为 {@link #validPricingAmount} 简化式）：免费档不得携带数值
     * （1007C0020）；付费档数值非法（非正数/分成超界）→ 1007C0020；切档时数值须显式携值
     * （付费档缺失 → 400——避免沿用他档数值的歧义）；档位未变时数值缺省 = 不变更。
     */
    private PricingUpdate validPricing(final String pricingModel, final BigDecimal priceAmount,
            final ProviderProductRow product) {
        final boolean modelChanged = pricingModel != null
                && !pricingModel.equals(product.pricingModel());
        final String modelName = pricingModel == null ? product.pricingModel() : pricingModel;
        if (FREE_MODEL.equals(modelName)) {
            if (priceAmount != null) {
                throw new CatalogBizException(CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE,
                        CatalogErrorCodes.PRODUCT_PRICE_FREE_WITH_AMOUNT_MESSAGE);
            }
            return new PricingUpdate(modelName, modelChanged ? null : product.priceAmount());
        }
        validPaidAmount(modelName, priceAmount);
        if (modelChanged && priceAmount == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "切换付费档位时须同时提供价格数值");
        }
        return new PricingUpdate(modelName, priceAmount == null ? product.priceAmount() : priceAmount);
    }

    /** 定价数值合法性（W8 ⑦，Q2-A：免费档携值 → 1007C0020；付费档草稿态可缺失，携值须为正数/分成 ≤100）。 */
    private void validPricingAmount(final String model, final BigDecimal priceAmount) {
        if (FREE_MODEL.equals(model)) {
            if (priceAmount != null) {
                throw new CatalogBizException(CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE,
                        CatalogErrorCodes.PRODUCT_PRICE_FREE_WITH_AMOUNT_MESSAGE);
            }
            return;
        }
        validPaidAmount(model, priceAmount);
    }

    /** 付费档数值校验（W8/W9 共用——C6 合并，0020 文案收敛为错误码常量旁单一口径）。 */
    private static void validPaidAmount(final String model, final BigDecimal priceAmount) {
        if (priceAmount == null) {
            return;
        }
        if (priceAmount.signum() <= 0) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE,
                    CatalogErrorCodes.PRODUCT_PRICE_AMOUNT_POSITIVE_MESSAGE);
        }
        if (PricingModel.REVENUE_SHARE.name().equals(model)
                && priceAmount.compareTo(SHARE_RATE_MAX) > 0) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_PRICE_INCOMPLETE,
                    CatalogErrorCodes.PRODUCT_PRICE_SHARE_RATE_MESSAGE);
        }
    }

    /** 简介必填校验（W8——规格行为 3 规则 5"要素必填"，S1 修复；W9 用 validIntro null=不变更）。 */
    private static String requireIntro(final String intro) {
        if (intro == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "产品简介不能为空");
        }
        return validIntro(intro);
    }

    /** 简介校验（W9：null = 不变更；空/超长 → 400）。 */
    private static String validIntro(final String intro) {
        if (intro == null) {
            return null;
        }
        if (intro.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "简介不能为空");
        }
        if (intro.length() > INTRO_MAX_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "简介超长（≤" + INTRO_MAX_LENGTH + " 字符）");
        }
        return intro;
    }

    /** 定价档位枚举校验（四档受控；非法 → 400）。 */
    private static String validPricingModel(final String pricingModel) {
        try {
            return PricingModel.valueOf(pricingModel).name();
        } catch (final IllegalArgumentException e) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "定价模型不在四档受控枚举范围内");
        }
    }

    /** 形态校验（四类受控枚举；W8 必填 required=true / W9 可选非法即拒——非法 → 400）。 */
    private static String validType(final String productType, final boolean required) {
        if (productType == null) {
            if (required) {
                throw new BizException(ErrorCodes.PARAM_INVALID, "产品形态不能为空");
            }
            return null;
        }
        try {
            return com.ctds.catalog.domain.DatasetType.valueOf(productType).name();
        } catch (final IllegalArgumentException e) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "产品形态不在四类受控枚举范围内");
        }
    }

    /** 类目校验（显式传入须为类目树节点，否则 1007C0014；缺省 = 不变更）。 */
    private String validCategory(final String categoryCode) {
        if (categoryCode == null) {
            return null;
        }
        if (!categoryPort.existsByCode(categoryCode)) {
            throw new CatalogBizException(CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE,
                    CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE_MESSAGE);
        }
        return categoryCode;
    }

    /**
     * 产品类目解析（W8 ⑥，Q2-A 传导）：显式传入 → 类目树成员校验（1007C0014）；缺省 → 按资源
     * declare_category 在 DB 侧归一化匹配定位类目码（匹配不到 = NULL，不阻断封装）。
     */
    private String resolveCategory(final String categoryCode, final Dataset dataset) {
        if (categoryCode != null) {
            return validCategory(categoryCode);
        }
        return categoryPort.findCodeByNormalizedName(dataset.declareCategory());
    }

    /** 乐观状态转换（并发漂移 → 1007C0019，沿 TOCTOU 防护先例）。 */
    private void transition(final ProviderProductRow product, final ProductStatus to,
            final LocalDateTime listedAt, final String action, final String summary) {
        final boolean moved = productRepository.transitionStatus(product.productId(), product.status(),
                to, listedAt, new ProductActionLog(null, product.productId(), action,
                        guard.requireSubject(), summary, LocalDateTime.now(clock)));
        if (!moved) {
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN,
                    CatalogErrorCodes.PRODUCT_STATE_FORBIDDEN_MESSAGE);
        }
    }

    /** 变更摘要拼接（field:from→to，分号分隔；单值截断 ≤64——SEC1 修复，summary 恒 ≤512）。 */
    private static void appendChange(final StringBuilder summary, final String field,
            final Object from, final Object to) {
        if (to == null || to.equals(from)) {
            return;
        }
        if (!summary.isEmpty()) {
            summary.append("；");
        }
        summary.append(field).append(":").append(truncateForSummary(from)).append("→")
                .append(truncateForSummary(to));
    }

    /** 摘要单值截断（超长值以"…(n 字符)"尾注承载，不落全文——留痕列宽硬保证）。 */
    private static String truncateForSummary(final Object value) {
        final String text = String.valueOf(value);
        return text.length() <= SUMMARY_VALUE_MAX_LENGTH ? text
                : text.substring(0, SUMMARY_VALUE_MAX_LENGTH) + "…(" + text.length() + " 字符)";
    }

    /** 定价组合结果（model/amount——amount 为 null = 库内现值不变更或免费档置空）。 */
    private record PricingUpdate(String modelName, BigDecimal amount) {
    }
}
