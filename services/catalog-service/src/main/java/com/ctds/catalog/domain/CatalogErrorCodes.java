package com.ctds.catalog.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 目录与资源服务码表（模块位 07，1007 段定稿，WBS-3.3.2 hifi §2；1000 平台/1001 国密/1002 幂等锁+KMS/
 * 1003 std-adapter/1004 主体/1005 DID/1006 空间已占用，占用留痕 ADR-016，沿 ADR-015 §3 惯例）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3）；精确 HTTP 映射见 interfaces.CatalogExceptionHandler。
 */
public final class CatalogErrorCodes {

    /** 同空间归一化后重名（含注销名锁定命中，行为 1 规则 5 / 行为 2 规则 3）。→ 409 */
    public static final ErrorCode DATASET_NAME_DUPLICATED = ErrorCode.of("1007C0001");
    public static final String DATASET_NAME_DUPLICATED_MESSAGE = "同一空间已存在同名资源";
    /** 注销名锁定命中文案（同码不同文案，沿 space 先例）。 */
    public static final String DATASET_NAME_LOCKED_MESSAGE = "资源名称已被本空间历史资源锁定，不可复用";
    /** 空间状态不可登记（未启用/冻结/解散一律拒绝，行为 1 规则 2——冻结拒新增 = 空间规格行为 2 规则 3 兑现）。→ 409 */
    public static final ErrorCode DATASET_SPACE_STATE_FORBIDDEN = ErrorCode.of("1007C0002");
    public static final String DATASET_SPACE_STATE_FORBIDDEN_MESSAGE = "空间当前状态不允许登记资源";
    /** 申报为重要数据，拒收登记（分级规范 §4.5-3 硬约束，代码强制，行为 1 规则 4）。→ 409 */
    public static final ErrorCode DATASET_IMPORTANT_REJECTED = ErrorCode.of("1007C0003");
    public static final String DATASET_IMPORTANT_REJECTED_MESSAGE = "申报为重要数据的资源暂不受理登记";
    /** 分类级别变更只能收紧就高（行为 2 规则 1——下调/放宽方向一律拒绝）。→ 409 */
    public static final ErrorCode DATASET_LEVEL_TIGHTEN_ONLY = ErrorCode.of("1007C0004");
    public static final String DATASET_LEVEL_TIGHTEN_ONLY_MESSAGE = "分类级别变更只能就高收紧，不可放宽";
    /** 资源不存在或无权访问（读面防枚举同形，行为 7 规则 2——不区分"不存在"与"非本人"）。→ 404 */
    public static final ErrorCode DATASET_NOT_FOUND_OR_NO_ACCESS = ErrorCode.of("1007C0005");
    public static final String DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE = "资源不存在或无权访问";
    /** 非登记主体无权操作（写面越权拒绝，行为 2 规则 5/行为 7 规则 1——含空间管理员，DENIED 留痕联动）。→ 403 */
    public static final ErrorCode DATASET_FORBIDDEN = ErrorCode.of("1007C0006");
    public static final String DATASET_FORBIDDEN_MESSAGE = "仅登记主体本人可执行该资源操作";
    /** 主体未入驻统一文案（行为 1 规则 1 防枚举：不区分"主体不存在"与"主体未入驻"；
     * 码值复用 1007C0006——hifi §2 码表未设独立资格码，hifi §1.1 W1 主要错误码列亦未单列，
     * 403 无权限语义下以本常量承载统一出站文案，实现口径已在交付说明登记）。 */
    public static final String ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法登记资源";
    /** 主体未入驻统一文案·目录场景（WBS-3.3.4 Q8-A：R6/R7/R8/W4/W6 检索与交互链同款防枚举口径，
     * 码值复用 1007C0006；与登记场景文案同源异表——目录域不表意"登记"）。 */
    public static final String CATALOG_ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法使用统一目录服务";
    /** 资源已注销，终态不可再操作（行为 2 规则 4——终态自环显式门槛）。→ 409 */
    public static final ErrorCode DATASET_ALREADY_DELETED = ErrorCode.of("1007C0007");
    public static final String DATASET_ALREADY_DELETED_MESSAGE = "资源已注销，不可再变更或注销";
    /** 名称为空/超长/含控制字符（行为 1 规则 5——归一化后校验）。→ 400 */
    public static final ErrorCode DATASET_NAME_INVALID = ErrorCode.of("1007C0008");
    public static final String DATASET_NAME_INVALID_MESSAGE = "资源名称不能为空或超出长度限制";
    /** 语义标签不在受控词表内（登记/变更，行为 1 规则 3「受控词表选取」——WBS-3.3.3 兑现）。→ 400 */
    public static final ErrorCode TAG_TERM_NOT_IN_VOCABULARY = ErrorCode.of("1007C0009");
    public static final String TAG_TERM_NOT_IN_VOCABULARY_MESSAGE = "语义标签不在受控词表范围内";
    /** 词表册不存在（读面路径码未命中，WBS-3.3.3 hifi §1.1 R4）。→ 404 */
    public static final ErrorCode TAG_VOCABULARY_NOT_FOUND = ErrorCode.of("1007C0010");
    public static final String TAG_VOCABULARY_NOT_FOUND_MESSAGE = "受控词表册不存在";
    /** 产品不存在或未在架（WBS-3.3.4 hifi §2：R7 未上架/不存在/已下架/已注销同形；W4/W6 新发起对非在架产品；
     * R8 产品行不存在与非订阅者同码同文案——一律不区分差异，防枚举，行为 7 规则 2 同源口径）。→ 404 */
    public static final ErrorCode PRODUCT_NOT_FOUND_OR_NOT_LISTED = ErrorCode.of("1007C0011");
    public static final String PRODUCT_NOT_FOUND_OR_NOT_LISTED_MESSAGE = "产品不存在或未在架";
    /** 本人收藏或订阅条目不存在（W5/W7 取消动作对无条目产品，hifi §2；剧本文案表意"记录不存在"）。→ 404 */
    public static final ErrorCode PRODUCT_RECORD_NOT_FOUND = ErrorCode.of("1007C0012");
    public static final String PRODUCT_RECORD_NOT_FOUND_MESSAGE = "收藏或订阅记录不存在";
    /** 检索参数不合法（R6 keyword 超长 / categoryCode 非类目树节点，WBS-3.3.4 hifi §2）。→ 400 */
    public static final ErrorCode CATALOG_SEARCH_PARAM_INVALID = ErrorCode.of("1007C0013");
    public static final String CATALOG_SEARCH_PARAM_INVALID_MESSAGE = "检索参数不合法";
    /** 分类申报不在平台受控类目范围内（登记/变更，WBS-3.3.4 hifi §2——不回显申报原文，
     * 沿 1007C0009 文案口径）。→ 400 */
    public static final ErrorCode CATEGORY_NOT_IN_CONTROLLED_TREE = ErrorCode.of("1007C0014");
    public static final String CATEGORY_NOT_IN_CONTROLLED_TREE_MESSAGE = "分类申报不在平台受控类目范围内";
    /** 非提供方本人无权操作该产品（写面越权拒绝，行为 4 规则 6/行为 7 规则 1——DENIED 留痕联动，
     * WBS-3.3.5 hifi §2；沿 1007C0006 模式）。→ 403 */
    public static final ErrorCode PRODUCT_FORBIDDEN = ErrorCode.of("1007C0015");
    public static final String PRODUCT_FORBIDDEN_MESSAGE = "仅提供方本人可执行该产品操作";
    /** 来源资源当前状态不可封装/上架（行为 3 规则 1/行为 4 规则 2——资源已注销或所在空间已解散，
     * 沿 1007C0002"状态不可登记"同款表意、主语为来源资源；C-3.3 剧本 S1-5/S2-4 判定面）。→ 409 */
    public static final ErrorCode PRODUCT_DATASET_STATE_FORBIDDEN = ErrorCode.of("1007C0016");
    public static final String PRODUCT_DATASET_STATE_FORBIDDEN_MESSAGE = "来源资源当前状态不允许封装或上架产品";
    /** 同一提供方下已存在同名产品（归一化判定，行为 3 规则 5；唯一键含已注销行——注销后同名不可
     * 复用，沿 dataset 名称锁同源口径；并发命中 uk_provider_norm_name 转译本码）。→ 409 */
    public static final ErrorCode PRODUCT_NAME_DUPLICATED = ErrorCode.of("1007C0017");
    public static final String PRODUCT_NAME_DUPLICATED_MESSAGE = "同一提供方下已存在同名产品";
    /** 产品名称为空/超长/含控制字符（行为 3 规则 5——归一化后校验，沿 1007C0008 口径）。→ 400 */
    public static final ErrorCode PRODUCT_NAME_INVALID = ErrorCode.of("1007C0018");
    public static final String PRODUCT_NAME_INVALID_MESSAGE = "产品名称不能为空或超出长度限制";
    /** 产品当前状态不允许该操作（状态机矩阵封闭，行为 4 规则 1——在架注销/未上架下架/已注销
     * 再动作/非法转换一律拒绝；C-3.3 剧本 S3-6 判定面）。→ 409 */
    public static final ErrorCode PRODUCT_STATE_FORBIDDEN = ErrorCode.of("1007C0019");
    public static final String PRODUCT_STATE_FORBIDDEN_MESSAGE = "产品当前状态不允许该操作";
    /** 定价信息不齐备或非法（行为 4 规则 2 上架前提，C-3.3 剧本 S2-2 判定面——付费档数值缺失/
     * 非正值/分成比例超界/免费档携带数值）。→ 409 */
    public static final ErrorCode PRODUCT_PRICE_INCOMPLETE = ErrorCode.of("1007C0020");
    public static final String PRODUCT_PRICE_INCOMPLETE_MESSAGE = "定价信息不齐备或非法，无法上架";
    /** 资源存在未注销产品引用、不得注销（行为 2 规则 2"先处理产品"——3.3.2 移交拒绝码随本卡定，
     * WBS-3.3.5 hifi §2；C-3.1 剧本 S3 步骤 4 判定面）。→ 409 */
    public static final ErrorCode PRODUCT_DATASET_REFERENCED = ErrorCode.of("1007C0021");
    public static final String PRODUCT_DATASET_REFERENCED_MESSAGE = "资源存在未注销产品引用，请先处理产品";
    /** 主体服务不可达/失败（UNAVAILABLE 统一文案，不冒充资格拒绝——hifi §5）。→ 503 */
    public static final ErrorCode SUBJECT_SERVICE_UNAVAILABLE = ErrorCode.of("1007S0001");
    public static final String SUBJECT_SERVICE_UNAVAILABLE_MESSAGE = "主体服务暂不可用，请稍后重试";
    /** 空间服务不可达/失败（UNAVAILABLE 统一文案，不冒充"非成员/空间不存在"——hifi §5）。→ 503 */
    public static final ErrorCode SPACE_SERVICE_UNAVAILABLE = ErrorCode.of("1007S0002");
    public static final String SPACE_SERVICE_UNAVAILABLE_MESSAGE = "空间服务暂不可用，请稍后重试";
    /**
     * 错误码尾号（dataset_action_log.reason_code 口径，hifi §3.3：DENIED 时落错误码尾号，
     * 如 1007C0003 → C0003；服务端常量，不含用户输入）。
     */
    public static String tailOf(final ErrorCode errorCode) {
        final String value = errorCode.value();
        return value.length() <= 5 ? value : value.substring(value.length() - 5);
    }

    private CatalogErrorCodes() {
    }
}
