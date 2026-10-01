package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetNameNormalizer;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.DeclareLevel;
import com.ctds.catalog.domain.ProductReferenceGuard;
import com.ctds.catalog.domain.SemanticTags;
import com.ctds.catalog.domain.TagTermPort;
import com.ctds.catalog.domain.TagVocabulary;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 资源命令服务（WBS-3.3.2 hifi §1.1 W2/W3）：登记入口（要素校验 + 归一化后交幂等登记服务）、
 * 变更（可变字段白名单 + 级别只能收紧）、注销（二次确认 + 引用保护前置检查 + 两写事务）。
 * 写面越权（非登记主体本人，含空间管理员）一律 1007C0006 + DENIED 留痕（行为 2 规则 5）；
 * 终态（已注销）再动作一律 1007C0007（行为 2 规则 4）。
 */
@Service
public class DatasetCommandService {

    /** 名称长度门槛（原始输入与归一化后同限；name 列 VARCHAR(128)，hifi §3.1）。 */
    private static final int NAME_MAX_LENGTH = 128;
    /** 简介长度上限（intro 列 VARCHAR(512)，hifi §3.1）。 */
    private static final int INTRO_MAX_LENGTH = 512;
    /** 分类申报长度上限（declare_category 列 VARCHAR(64)，hifi §3.1）。 */
    private static final int CATEGORY_MAX_LENGTH = 64;
    /** 变更留痕动作码。 */
    private static final String ACTION_UPDATE = "UPDATE";
    /** 注销留痕动作码。 */
    private static final String ACTION_CANCEL = "CANCEL";
    /** 变更拒绝留痕动作码。 */
    private static final String ACTION_DENIED_UPDATE = "DENIED_UPDATE";
    /** 注销拒绝留痕动作码。 */
    private static final String ACTION_DENIED_CANCEL = "DENIED_CANCEL";
    /** 注销二次确认缺失文案（缺省/null/false 一律拒绝，沿 confirmDissolve 先例）。 */
    private static final String CANCEL_CONFIRM_REQUIRED_MESSAGE = "缺少注销二次确认（confirmCancellation 必须为 true）";

    private final DatasetRepository repository;
    private final DatasetRegistrationService registrationService;
    private final ProductReferenceGuard productReferenceGuard;
    private final CatalogAccessGuard guard;
    private final TagTermPort tagTermPort;
    private final CategoryPort categoryPort;
    private final Clock clock;

    public DatasetCommandService(final DatasetRepository repository,
            final DatasetRegistrationService registrationService, final ProductReferenceGuard guard,
            final CatalogAccessGuard accessGuard, final TagTermPort tagTermPort,
            final CategoryPort categoryPort, final Clock clock) {
        this.repository = repository;
        this.registrationService = registrationService;
        this.productReferenceGuard = guard;
        this.guard = accessGuard;
        this.tagTermPort = tagTermPort;
        this.categoryPort = categoryPort;
        this.clock = clock;
    }

    // ==== 登记（行为 1；校验 + 归一化后交幂等边界）====

    /**
     * 登记入口：要素校验（名称类问题 1007C0008；其余要素 400 通用参数码）→ 归一化 →
     * 交幂等登记服务（语义标签成员校验在其链序第 6′ 步——WBS-3.3.3 hifi §4.1，跨 Bean 调用沿先例）。
     */
    public Dataset create(final long spaceId, final CreateDatasetCommand request) {
        final String subject = guard.requireSubject();
        final String name = request.name();
        if (name == null || name.isBlank()) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_INVALID, "资源名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_INVALID,
                    "资源名称超长（≤" + NAME_MAX_LENGTH + " 字符）");
        }
        final String normalizedName = DatasetNameNormalizer.normalize(name);
        if (normalizedName.isEmpty()) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_INVALID, "资源名称不能为空");
        }
        if (normalizedName.length() > NAME_MAX_LENGTH) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_INVALID,
                    "资源名称超长（归一化后 ≤" + NAME_MAX_LENGTH + " 字符）");
        }
        final List<String> problems = new ArrayList<>();
        if (request.type() == null) {
            problems.add("资源类型不能为空");
        }
        if (request.intro() == null || request.intro().isBlank()) {
            problems.add("简介不能为空");
        } else if (request.intro().length() > INTRO_MAX_LENGTH) {
            problems.add("简介超长（≤" + INTRO_MAX_LENGTH + " 字符）");
        }
        problems.addAll(SemanticTags.validate(request.semanticTags()));
        if (request.declareCategory() == null || request.declareCategory().isBlank()) {
            problems.add("分类申报不能为空");
        } else if (request.declareCategory().length() > CATEGORY_MAX_LENGTH) {
            problems.add("分类申报超长（≤" + CATEGORY_MAX_LENGTH + " 字符）");
        }
        if (request.declareLevel() == null) {
            problems.add("分级申报不能为空");
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, String.join("；", problems));
        }
        final CreateDatasetCommand command = new CreateDatasetCommand(spaceId, subject, name, normalizedName,
                request.type(), request.intro(), request.semanticTags(), request.declareCategory(),
                request.declareLevel(), request.declareImportant());
        final Dataset created = registrationService.register(command);
        // 沿 space DB-28 同族修复：幂等命中（ADR-007 模式 B 返回首次结果）不经过登记服务方法体的
        // 名称锁定判定，且命中返回值是登记时快照（status 恒为 ACTIVE）——命中资源其后是否已注销
        // 须按 id 重读当前行判定；已注销即按名称锁口径拒绝（行为 2 规则 3，注销与锁定同事务写入），
        // 不以 200 返回已注销资源的登记结果（剧本 S3 步骤 2"同名再登记被拒"必须成立）。
        final Dataset current = load(created.id());
        if (current.status() == DatasetStatus.DELETED) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_DUPLICATED,
                    CatalogErrorCodes.DATASET_NAME_LOCKED_MESSAGE);
        }
        return current;
    }

    // ==== 变更（行为 2 规则 1）====

    /**
     * 变更：仅登记主体本人（非本人含空间管理员 → 1007C0006 + DENIED 留痕）；终态拒绝（1007C0007）；
     * 逐字段留痕 from→to；分级申报只能收紧就高（下调 → 1007C0004 + DENIED 留痕）。
     * 判定顺序：存在性（1007C0005）→ 属主（1007C0006）→ 终态（1007C0007）→ 字段校验（400）→ 级别门槛。
     */
    public Dataset update(final long datasetId, final DatasetUpdateRequest request) {
        final String subject = guard.requireSubject();
        if (request.isEmpty()) {
            throw new BizException(ErrorCodes.PARAM_INVALID,
                    "至少提供一项可变更字段（简介/语义标签/分类申报/分级申报）");
        }
        final Dataset dataset = load(datasetId);
        requireOwner(dataset, subject, ACTION_DENIED_UPDATE);
        requireActive(dataset);
        final String intro = validIntro(request.intro());
        final String tagsJson = validTagsJson(request.semanticTags());
        final String category = validCategory(request.declareCategory());
        requireTightened(dataset, request.declareLevel(), subject);
        final LocalDateTime now = LocalDateTime.now(clock);
        final String newIntro = changedOrNull(intro, dataset.intro());
        final String newTags = changedOrNull(tagsJson, dataset.semanticTagsJson());
        final String newCategory = changedOrNull(category, dataset.declareCategory());
        final DeclareLevel newLevel = request.declareLevel() == null
                || request.declareLevel() == dataset.declareLevel() ? null : request.declareLevel();
        final List<DatasetActionLog> logs = new ArrayList<>();
        if (newIntro != null) {
            logs.add(updateLog(dataset, subject, "intro", dataset.intro(), newIntro, now));
        }
        if (newTags != null) {
            logs.add(updateLog(dataset, subject, "semanticTags", tagsText(dataset.semanticTagsJson()),
                    tagsText(newTags), now));
        }
        if (newCategory != null) {
            logs.add(updateLog(dataset, subject, "declareCategory", dataset.declareCategory(), newCategory,
                    now));
        }
        if (newLevel != null) {
            logs.add(updateLog(dataset, subject, "declareLevel", dataset.declareLevel().name(),
                    newLevel.name(), now));
        }
        repository.updateFields(datasetId,
                new DatasetRepository.DatasetUpdate(newIntro, newTags, newCategory, newLevel), logs);
        return load(datasetId);
    }

    /** 变更请求载荷（null = 不变更该项；可变字段白名单 = 简介/标签/分类/级别，hifi §1.1 W2）。 */
    public record DatasetUpdateRequest(String intro, List<String> semanticTags, String declareCategory,
            DeclareLevel declareLevel) {

        /** 是否为空变更（全部字段缺省，无可变更项 → 400 通用参数码）。 */
        public boolean isEmpty() {
            return intro == null && semanticTags == null && declareCategory == null && declareLevel == null;
        }
    }

    // ==== 注销（行为 2 规则 2/3）====

    /**
     * 注销：仅登记主体本人（非本人 → 1007C0006 + DENIED 留痕）；终态拒绝（1007C0007）；
     * 二次确认 confirmCancellation 显式 true 必填（缺省/null/false → 400 通用参数码）；
     * 引用保护前置检查（本卡恒放行，3.3.5 产品表落地后实体化）；两写事务 + 名称锁定。
     */
    public Dataset cancel(final long datasetId, final Boolean confirmCancellation) {
        final String subject = guard.requireSubject();
        final Dataset dataset = load(datasetId);
        requireOwner(dataset, subject, ACTION_DENIED_CANCEL);
        requireActive(dataset);
        if (!Boolean.TRUE.equals(confirmCancellation)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, CANCEL_CONFIRM_REQUIRED_MESSAGE);
        }
        if (productReferenceGuard.hasActiveProductReferences(datasetId)) {
            // 3.3.5 移交：产品表落地后本分支替换为业务码拒绝（错误码随 3.3.5 定，hifi §4.4）；
            // 本卡实现恒 false、分支不可达——保留显式 fail-closed 出口（不静默放行）
            throw new IllegalStateException("资源存在未注销产品引用，拒绝注销（错误码待 3.3.5 定稿）");
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final DatasetActionLog log = new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                ACTION_CANCEL, dataset.status().name(), DatasetStatus.DELETED.name(), ActionResult.SUCCESS,
                null, now);
        repository.cancel(datasetId, dataset.spaceId(), dataset.normalizedName(), log);
        return load(datasetId);
    }

    // ==== 内部 ====

    private Dataset load(final long datasetId) {
        return repository.findById(datasetId)
                .orElseThrow(() -> new CatalogBizException(
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS,
                        CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE));
    }

    /** 属主门槛（行为 2 规则 5）：非登记主体本人（含空间管理员）→ DENIED 留痕 + 1007C0006。 */
    private void requireOwner(final Dataset dataset, final String subject, final String denyAction) {
        if (!dataset.ownerSubjectNo().equals(subject)) {
            repository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                    denyAction, dataset.status().name(), null, ActionResult.DENIED,
                    CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_FORBIDDEN),
                    LocalDateTime.now(clock)));
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.DATASET_FORBIDDEN_MESSAGE);
        }
    }

    /** 终态门槛（行为 2 规则 4）：已注销资源一切变更/注销再动作 → 1007C0007。 */
    private void requireActive(final Dataset dataset) {
        if (dataset.status() == DatasetStatus.DELETED) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_ALREADY_DELETED,
                    CatalogErrorCodes.DATASET_ALREADY_DELETED_MESSAGE);
        }
    }

    /**
     * 语义标签成员校验（行为 1 规则 3）：任一标签不在受控词表 → 1007C0009（400）。
     * 拒绝文案为服务端常量、<b>不回显被拒标签原文</b>（章程 4.3 + 防输入回显）。
     */
    private void requireTermMembership(final List<String> tags) {
        if (!tagTermPort.findUnmatched(TagVocabulary.SEMANTIC_TAG, tags).isEmpty()) {
            throw new CatalogBizException(CatalogErrorCodes.TAG_TERM_NOT_IN_VOCABULARY,
                    CatalogErrorCodes.TAG_TERM_NOT_IN_VOCABULARY_MESSAGE);
        }
    }

    /** 简介校验（缺省 = 不变更；空串/超长 → 400）。 */
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

    /** 语义标签载体级校验 + 词表成员校验 + JSON 化（缺省 = 不变更；载体问题 → 400，非词表标签 → 1007C0009）。 */
    private String validTagsJson(final List<String> tags) {
        if (tags == null) {
            return null;
        }
        final List<String> problems = SemanticTags.validate(tags);
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, String.join("；", problems));
        }
        requireTermMembership(tags);
        return SemanticTags.toJson(tags);
    }

    /**
     * 分类申报校验（缺省 = 不变更；空串/超长 → 400）+ 类目成员校验（WBS-3.3.4 hifi §4.1 组内后位：
     * declare_category 在变更可变字段集内（hifi §10.6 实测确认），与登记路径同链序——组内词表校验
     * （validTagsJson）先、类目校验后；非受控类目 → 1007C0014，文案不回显申报原文；
     * 非法入参零副作用——无部分写入、零留痕）。
     */
    private String validCategory(final String category) {
        if (category == null) {
            return null;
        }
        if (category.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "分类申报不能为空");
        }
        if (category.length() > CATEGORY_MAX_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID,
                    "分类申报超长（≤" + CATEGORY_MAX_LENGTH + " 字符）");
        }
        if (!categoryPort.existsByNormalizedName(DatasetNameNormalizer.normalize(category))) {
            throw new CatalogBizException(CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE,
                    CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE_MESSAGE);
        }
        return category;
    }

    /** 分级申报收紧门槛（行为 2 规则 1）：下调/放宽 → DENIED 留痕 + 1007C0004（缺省 = 不变更）。 */
    private void requireTightened(final Dataset dataset, final DeclareLevel newLevel, final String subject) {
        if (newLevel == null || newLevel.isTightenedFrom(dataset.declareLevel())) {
            return;
        }
        repository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                ACTION_DENIED_UPDATE, dataset.declareLevel().name(), newLevel.name(), ActionResult.DENIED,
                CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_LEVEL_TIGHTEN_ONLY),
                LocalDateTime.now(clock)));
        throw new CatalogBizException(CatalogErrorCodes.DATASET_LEVEL_TIGHTEN_ONLY,
                CatalogErrorCodes.DATASET_LEVEL_TIGHTEN_ONLY_MESSAGE);
    }

    private DatasetActionLog updateLog(final Dataset dataset, final String subject, final String field,
            final String fromValue, final String toValue, final LocalDateTime now) {
        return new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(), ACTION_UPDATE,
                field + ":" + fromValue, field + ":" + toValue, ActionResult.SUCCESS, null, now);
    }

    private static String changedOrNull(final String newValue, final String oldValue) {
        return newValue == null || newValue.equals(oldValue) ? null : newValue;
    }

    /** 语义标签留痕摘要（列表 → 逗号分隔业务可读文本；留痕列宽 1024）。 */
    private static String tagsText(final String tagsJson) {
        return String.join(",", SemanticTags.fromJson(tagsJson));
    }
}
