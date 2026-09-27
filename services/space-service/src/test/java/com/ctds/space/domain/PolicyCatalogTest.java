package com.ctds.space.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 策略目录封闭性单测（WBS-3.2.5 hifi §8 T15，规格行为 7 规则 6"键命名与可配置项集合随 3.2.5 定稿"）：
 * 键集合恰为定稿 3 键且按声明序、每键值域封闭、严格度单射（同键无并列严格度——同严格度即同值）、
 * 越域值兜底 0012、放宽判定方向正确。增删键/值即红灯（目录 = 空间域策略语言权威定义点，扩目录须登记）。
 */
class PolicyCatalogTest {

    @Test
    void catalogIsClosedToThreeDeclaredKeysInDeclarationOrder() {
        assertThat(PolicyCatalog.keys()).containsExactly(
                "data.visibility", "data.retention", "member.data_export");
    }

    @Test
    void visibilityValueDomainIsClosedWithStrictnessOrdering() {
        final PolicyCatalog.EntryDefinition definition = PolicyCatalog.find("data.visibility").orElseThrow();
        assertThat(definition.displayName()).isEqualTo("数据可见范围");
        assertThat(definition.strictness()).containsOnlyKeys("SPACE_MEMBER", "ALL_PLATFORM");
        assertThat(PolicyCatalog.strictnessOf(definition, "SPACE_MEMBER")).isEqualTo(2);
        assertThat(PolicyCatalog.strictnessOf(definition, "ALL_PLATFORM")).isEqualTo(1);
    }

    @Test
    void retentionValueDomainIsClosedWithStrictnessOrdering() {
        final PolicyCatalog.EntryDefinition definition = PolicyCatalog.find("data.retention").orElseThrow();
        assertThat(definition.displayName()).isEqualTo("数据留存期限");
        assertThat(definition.strictness()).containsOnlyKeys("D30", "D90", "D180", "D365");
        assertThat(PolicyCatalog.strictnessOf(definition, "D30")).isEqualTo(4);
        assertThat(PolicyCatalog.strictnessOf(definition, "D365")).isEqualTo(1);
    }

    @Test
    void dataExportValueDomainIsClosedWithStrictnessOrdering() {
        final PolicyCatalog.EntryDefinition definition = PolicyCatalog.find("member.data_export")
                .orElseThrow();
        assertThat(definition.displayName()).isEqualTo("成员数据导出");
        assertThat(definition.strictness()).containsOnlyKeys("FORBIDDEN", "APPROVAL_REQUIRED", "ALLOWED");
        assertThat(PolicyCatalog.strictnessOf(definition, "FORBIDDEN")).isEqualTo(3);
        assertThat(PolicyCatalog.strictnessOf(definition, "ALLOWED")).isEqualTo(1);
    }

    @Test
    void strictnessIsInjectiveWithinEveryKeyDomain() {
        // 严格度单射：同键值域内无并列严格度（同严格度即同值，消除取严平局歧义——hifi §3）
        for (final String key : PolicyCatalog.keys()) {
            final PolicyCatalog.EntryDefinition definition = PolicyCatalog.find(key).orElseThrow();
            final Set<Integer> strictnessValues = new HashSet<>(definition.strictness().values());
            assertThat(strictnessValues).as("键 %s 严格度应单射", key).hasSize(definition.strictness().size());
        }
    }

    @Test
    void unknownKeyIsRejectedByCatalogLookup() {
        assertThat(PolicyCatalog.isDefined("bogus.key")).isFalse();
        assertThat(PolicyCatalog.find("bogus.key")).isEmpty();
        assertThat(PolicyCatalog.find("DATA.VISIBILITY")).as("键大小写敏感").isEmpty();
    }

    @Test
    void outOfDomainValueOnStrictnessLookupIsRejectedWithPolicyEntryInvalid() {
        // 越域值直查 → 0012 兜底而非 NPE（hifi §8 T15"越域查询异常"；4 视角评审②/④跟踪项收口：
        // 防"库内数据与目录漂移"的防御纵深，写路径另有先行值域校验）
        final PolicyCatalog.EntryDefinition definition = PolicyCatalog.find("data.visibility")
                .orElseThrow();
        assertThatThrownBy(() -> PolicyCatalog.strictnessOf(definition, "NOT_IN_DOMAIN"))
                .isInstanceOfSatisfying(SpaceBizException.class, e ->
                        assertThat(e.getErrorCode().value()).isEqualTo("1006C0012"));
    }

    @Test
    void looseningDirectionIsDetectedForRedlineGate() {
        final PolicyCatalog.EntryDefinition visibility = PolicyCatalog.find("data.visibility").orElseThrow();
        // 放宽：严 → 宽（SPACE_MEMBER → ALL_PLATFORM）——行为 7 规则 2 拒绝方向（§6.5 代码强制）
        assertThat(PolicyCatalog.isLoosening(visibility, "SPACE_MEMBER", "ALL_PLATFORM")).isTrue();
        // 收紧：宽 → 严——允许方向
        assertThat(PolicyCatalog.isLoosening(visibility, "ALL_PLATFORM", "SPACE_MEMBER")).isFalse();
        final PolicyCatalog.EntryDefinition retention = PolicyCatalog.find("data.retention").orElseThrow();
        assertThat(PolicyCatalog.isLoosening(retention, "D90", "D30")).as("缩短期限 = 收紧").isFalse();
        assertThat(PolicyCatalog.isLoosening(retention, "D90", "D180")).as("延长期限 = 放宽").isTrue();
        assertThat(PolicyCatalog.isLoosening(retention, "D90", "D90")).as("同值非放宽").isFalse();
    }
}
