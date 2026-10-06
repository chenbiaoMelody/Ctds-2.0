package com.ctds.contract.domain.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 策略要素目录注册表（WBS-3.4.4 hifi §3，DSL v1.0 五要素权威定义点）。
 *
 * <p>与空间域 {@code com.ctds.space.domain.PolicyCatalog} 模式同构（Q2-A 同构不共码，
 * ADR-019 留痕）：单点权威注册表 / 键命名规范一致（"域.名词小写点分"）/ 封闭约束
 * （本域 = 强类型 + 约束说明）/ 扩要素 = 注册表追加行登记。两层命名分离：目录键 = 模型层
 * 标识（治理/渲染/语义标注用），文档字段名 = 载荷兼容层（沿 3.4.3 固定字段序，不动）。</p>
 *
 * <p>判定语义标注（judgmentSemantics）供 3.4.5 引擎消费的契约面——本卡只标注不实现；
 * 未定义项（计数口径/用途词表化/地域编码化/相对期限换算）随 3.4.5 设计落定。</p>
 */
public final class PolicyElementCatalog {

    /** 要素定义：目录键 / 文档字段名 / 显示名 / 值类型 / 约束 / 判定语义标注（hifi §2.2 表逐行）。 */
    public record ElementDefinition(String key, String field, String displayName,
            String valueType, String constraint, String judgmentSemantics) {
    }

    private static final Map<String, ElementDefinition> ELEMENTS = new LinkedHashMap<>();
    private static final Map<String, ElementDefinition> BY_FIELD = new LinkedHashMap<>();

    static {
        // 五要素权威定稿（hifi §2.2 表逐行；声明序 = 目录键稳定排序）
        register(new ElementDefinition("usage.quota", UsagePolicyDsl.FIELD_QUOTA, "使用次数上限",
                "integer", "≥ 1",
                "使用次数上限；计数口径（按调用/按交付）= 3.4.5 未定义项"));
        register(new ElementDefinition("usage.term", UsagePolicyDsl.FIELD_TERM, "使用期限",
                "date-range", "起止有序；起始日 ≥ 提交日",
                "期限内有效；相对期限（自生效起 N 天）自动换算 = 3.4.5 未定义项，"
                        + "演示载荷由技术侧按绝对日期构造"));
        register(new ElementDefinition("usage.purpose", UsagePolicyDsl.FIELD_PURPOSE, "用途限定",
                "text", "trim 后非空",
                "精确等值匹配（约定外用途拒绝）；词表化/多值 = 3.4.5 未定义项"));
        register(new ElementDefinition("usage.territory", UsagePolicyDsl.FIELD_TERRITORY,
                "域内使用", "text", "trim 后非空",
                "精确等值匹配（域外拒绝）；地域编码化 = 3.4.5 未定义项"));
        register(new ElementDefinition("usage.no_redistribution",
                UsagePolicyDsl.FIELD_NO_REDISTRIBUTION, "禁止再分发", "boolean",
                "enabled=true 即禁止", "再分发动作（转授/转售/对外提供）拦截"));
    }

    private PolicyElementCatalog() {
    }

    private static void register(final ElementDefinition definition) {
        ELEMENTS.put(definition.key(), definition);
        BY_FIELD.put(definition.field(), definition);
    }

    /** 按目录键取定义（未注册键返回 empty）。 */
    public static Optional<ElementDefinition> find(final String key) {
        return Optional.ofNullable(ELEMENTS.get(key));
    }

    /** 按文档字段名取定义（未注册字段名返回 empty）。 */
    public static Optional<ElementDefinition> findByField(final String field) {
        return Optional.ofNullable(BY_FIELD.get(field));
    }

    /** 目录键是否已注册（目录封闭性判定）。 */
    public static boolean isDefined(final String key) {
        return ELEMENTS.containsKey(key);
    }

    /** 已注册目录键集（按声明序，稳定排序——治理/渲染排序依据）。 */
    public static Set<String> keys() {
        return Collections.unmodifiableSet(ELEMENTS.keySet());
    }

    /** 文档字段名集（按声明序——解析器未知要素封闭性判定用，R2）。 */
    public static Set<String> fields() {
        return Collections.unmodifiableSet(BY_FIELD.keySet());
    }
}
