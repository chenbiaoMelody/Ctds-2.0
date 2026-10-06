package com.ctds.contract.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 策略要素目录单测（WBS-3.4.4 hifi §6 目录面）：五要素键集与规格一一对应（行为 4 规则 2）；
 * 键命名规范匹配（"域.名词小写点分"，行为 4 规则 3 形态对齐）；fields() 与文档字段名映射一致
 * （两层命名分离，ADR-019 留痕）；目录封闭性判定；元数据齐备（3.4.5 引擎 / 3.4.7 界面消费面）。
 */
class PolicyElementCatalogTest {

    /** 规格行为 4 规则 2 五要素（键集一一对应；声明序 = hifi §2.2 表行序）。 */
    private static final List<String> SPEC_KEYS = List.of("usage.quota", "usage.term",
            "usage.purpose", "usage.territory", "usage.no_redistribution");

    @Test
    void fiveElementKeysMatchSpecInDeclaredOrder() {
        assertThat(PolicyElementCatalog.keys()).containsExactlyElementsOf(SPEC_KEYS);
    }

    @Test
    void keyNamingConventionHolds() {
        // "域.名词小写点分"（对齐空间 PolicyCatalog 键命名规范——Q2-A 同构契约）
        for (final String key : PolicyElementCatalog.keys()) {
            assertThat(key).as("目录键 %s", key).matches("usage\\.[a-z0-9_]+");
        }
    }

    @Test
    void fieldsMapToDocumentFieldNames() {
        // 两层命名分离：文档字段名 = 载荷兼容层（沿 3.4.3 固定字段序）
        assertThat(PolicyElementCatalog.fields()).containsExactly("quota", "term", "purpose",
                "territory", "noRedistribution");
        for (final String field : PolicyElementCatalog.fields()) {
            assertThat(PolicyElementCatalog.findByField(field))
                    .as("文档字段名 %s 须映射到目录要素", field).isPresent();
        }
        assertThat(PolicyElementCatalog.findByField("quota").orElseThrow().key())
                .isEqualTo("usage.quota");
        assertThat(PolicyElementCatalog.findByField("noRedistribution").orElseThrow().key())
                .isEqualTo("usage.no_redistribution");
    }

    @Test
    void closedSetJudgment() {
        for (final String key : SPEC_KEYS) {
            assertThat(PolicyElementCatalog.isDefined(key)).as("已知键 %s", key).isTrue();
            assertThat(PolicyElementCatalog.find(key)).as("已知键 %s", key).isPresent();
        }
        assertThat(PolicyElementCatalog.isDefined("usage.foo")).isFalse();
        assertThat(PolicyElementCatalog.find("usage.foo")).isEmpty();
        assertThat(PolicyElementCatalog.findByField("foo")).isEmpty();
    }

    @Test
    void metadataCompleteForEngineAndUiConsumers() {
        // 3.4.5 引擎消费判定语义标注；3.4.7 界面按目录渲染表单（要素集合零自持）——元数据须齐备
        for (final String key : PolicyElementCatalog.keys()) {
            final PolicyElementCatalog.ElementDefinition definition =
                    PolicyElementCatalog.find(key).orElseThrow();
            assertThat(definition.displayName()).as("%s 显示名", key).isNotBlank();
            assertThat(definition.valueType()).as("%s 值类型", key).isNotBlank();
            assertThat(definition.constraint()).as("%s 约束", key).isNotBlank();
            assertThat(definition.judgmentSemantics()).as("%s 判定语义", key).isNotBlank();
        }
        assertThat(PolicyElementCatalog.find("usage.quota").orElseThrow().displayName())
                .isEqualTo("使用次数上限");
        assertThat(PolicyElementCatalog.find("usage.quota").orElseThrow().valueType())
                .isEqualTo("integer");
        assertThat(PolicyElementCatalog.find("usage.term").orElseThrow().valueType())
                .isEqualTo("date-range");
        assertThat(PolicyElementCatalog.find("usage.no_redistribution").orElseThrow().valueType())
                .isEqualTo("boolean");
    }
}
