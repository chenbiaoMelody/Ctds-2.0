package com.ctds.contract.domain.policy;

/**
 * 使用控制策略 DSL v1.0 版本与文档字段名契约常量（WBS-3.4.4 hifi §2，Q1-A 结构化声明式
 * 策略文档）：文档载荷形态沿 3.4.3 固定字段序（兼容层零破坏），本 DSL 新增的唯一字段为
 * 可选版本位 {@code dslVersion}（缺省 = "1.0"，未知值 fail-closed 拒绝——R3）。
 *
 * <p>要素集合与元数据的权威定义点为 {@link PolicyElementCatalog}（封闭集判定以目录
 * {@code fields()} 为据）；本类只承载字段名与版本常量，禁止两处并行定义要素清单。</p>
 */
public final class UsagePolicyDsl {

    /** 当前支持的 DSL 版本（演进 = 新版本值 + 解析器多版本分支 + 兼容映射批——ADR-019）。 */
    public static final String DSL_VERSION = "1.0";

    /** 版本字段名（可选；缺省容忍 = "1.0"——3.4.3 存量载荷零破坏）。 */
    public static final String FIELD_DSL_VERSION = "dslVersion";

    /** 次数要素文档字段名（兼容层）。 */
    public static final String FIELD_QUOTA = "quota";

    /** 期限要素文档字段名（兼容层）。 */
    public static final String FIELD_TERM = "term";

    /** 用途要素文档字段名（兼容层）。 */
    public static final String FIELD_PURPOSE = "purpose";

    /** 域内要素文档字段名（兼容层）。 */
    public static final String FIELD_TERRITORY = "territory";

    /** 禁止再分发要素文档字段名（兼容层）。 */
    public static final String FIELD_NO_REDISTRIBUTION = "noRedistribution";

    /** 显式"无使用限制"声明位（布尔；不是要素——不入目录键集）。 */
    public static final String FIELD_NO_RESTRICTION_DECLARED = "noRestrictionDeclared";

    /** 要素启用开关叶子字段名（禁用要素仅落 enabled 布尔）。 */
    public static final String FIELD_ENABLED = "enabled";

    /** 次数要素叶子字段名。 */
    public static final String FIELD_MAX_COUNT = "maxCount";

    /** 期限要素起始日叶子字段名（ISO 本地日期 YYYY-MM-DD）。 */
    public static final String FIELD_START_DATE = "startDate";

    /** 期限要素截止日叶子字段名（ISO 本地日期 YYYY-MM-DD）。 */
    public static final String FIELD_END_DATE = "endDate";

    /** 文本要素叶子字段名（用途/域内）。 */
    public static final String FIELD_TEXT = "text";

    private UsagePolicyDsl() {
    }
}
