package com.ctds.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 目录域值域与载体单测（WBS-3.3.2 hifi §6 T13 枚举封闭 + 归一化/标签载体承诺；
 * WBS-3.3.3 hifi §5 T8 受控词表领域单测）：枚举值域封闭（受控枚举不得被隐式扩展）；
 * 级别收紧方向矩阵；归一化口径（控制字符去除、U+3000/U+00A0 显式空白集、折叠与 trim）；
 * 语义标签载体级校验与 JSON 往返；受控词表实体构造约束与词条编号形态。
 * （成员校验差集口径不在本类：评审循环 1 处置——桩端口对生产唯一实现零证伪力，
 * 已移至 {@code CatalogTagVocabularyIntegrationTest} 对生产仓储实跑。）
 */
class CatalogDomainTest {

    @Test
    void datasetTypeIsClosed() {
        assertThat(DatasetType.values()).extracting(Enum::name)
                .containsExactly("DATASET", "API", "REPORT", "MODEL");
    }

    @Test
    void declareLevelIsClosedAndOrdered() {
        assertThat(DeclareLevel.values()).extracting(Enum::name)
                .containsExactly("L1", "L2", "L3", "L4");
        // 级别序即 ordinal（L1 最低、L4 最高）：就高收紧 = ordinal 不减
        assertThat(DeclareLevel.L1.ordinal()).isLessThan(DeclareLevel.L4.ordinal());
    }

    @Test
    void tightenOnlyMatrix() {
        // 上调与同级 = 收紧方向（放行）；下调 = 放宽（拒绝）——行为 2 规则 1
        assertThat(DeclareLevel.L3.isTightenedFrom(DeclareLevel.L2)).isTrue();
        assertThat(DeclareLevel.L2.isTightenedFrom(DeclareLevel.L2)).isTrue();
        assertThat(DeclareLevel.L1.isTightenedFrom(DeclareLevel.L4)).isFalse();
        assertThat(DeclareLevel.L2.isTightenedFrom(DeclareLevel.L3)).isFalse();
    }

    @Test
    void datasetStatusIsClosedTerminalPair() {
        assertThat(DatasetStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "DELETED");
    }

    @Test
    void actionResultIsClosed() {
        assertThat(ActionResult.values()).extracting(Enum::name).containsExactly("SUCCESS", "DENIED");
    }

    @Test
    void normalizerRemovesControlCharsAndFoldsWhitespace() {
        // 控制字符与格式字符（含零宽）去除
        assertThat(DatasetNameNormalizer.normalize("普\u0000惠\u200B金融\uFEFF数据集"))
                .isEqualTo("普惠金融数据集");
        // 首尾空白 trim + 内部连续空白折叠为单空格；制表符属控制字符（Cc）先被去除、不作为分隔符
        assertThat(DatasetNameNormalizer.normalize("  普惠   金融 数据集  ")).isEqualTo("普惠 金融 数据集");
        assertThat(DatasetNameNormalizer.normalize("金融\t数据集")).isEqualTo("金融数据集");
        // U+3000 全角空格与 U+00A0 不换行空格并入空白集（绕过探针口径）
        assertThat(DatasetNameNormalizer.normalize("普惠金融数据集\u3000")).isEqualTo("普惠金融数据集");
        assertThat(DatasetNameNormalizer.normalize("\u00A0普惠金融数据集")).isEqualTo("普惠金融数据集");
        // null / 空 / 纯空白 → 空串（调用方按"名称不能为空"处置）
        assertThat(DatasetNameNormalizer.normalize(null)).isEmpty();
        assertThat(DatasetNameNormalizer.normalize("   ")).isEmpty();
        assertThat(DatasetNameNormalizer.normalize("\u3000\u3000")).isEmpty();
    }

    @Test
    void semanticTagsCarrierValidation() {
        // 正常载体放行
        assertThat(SemanticTags.validate(List.of("金融", "普惠"))).isEmpty();
        // 必填 / 空项 / 超量 / 超长 / 重复 → 拒绝（载体级，Q7-A；词表成员校验归 3.3.3）
        assertThat(SemanticTags.validate(null)).isNotEmpty();
        assertThat(SemanticTags.validate(List.of())).isNotEmpty();
        assertThat(SemanticTags.validate(List.of(" "))).isNotEmpty();
        assertThat(SemanticTags.validate(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
                .isNotEmpty();
        assertThat(SemanticTags.validate(List.of("长".repeat(33)))).isNotEmpty();
        assertThat(SemanticTags.validate(List.of("金融", "金融"))).isNotEmpty();
        // 数量上限边界（10 项放行）
        final Set<String> ten = new LinkedHashSet<>();
        for (int i = 0; i < 10; i++) {
            ten.add("标签" + i);
        }
        assertThat(SemanticTags.validate(List.copyOf(ten))).isEmpty();
    }

    @Test
    void semanticTagsJsonRoundTrip() {
        final List<String> tags = List.of("金融", "普惠");
        final String json = SemanticTags.toJson(tags);
        assertThat(json).isEqualTo("[\"金融\",\"普惠\"]");
        assertThat(SemanticTags.fromJson(json)).isEqualTo(tags);
        // 空载体读面回空列表（不抛错）
        assertThat(SemanticTags.fromJson("")).isEmpty();
        assertThat(SemanticTags.fromJson(null)).isEmpty();
    }

    @Test
    void semanticTagsInvalidJsonFailsLoudly() {
        // 载体被污染（非法 JSON / 非数组结构）→ 显式失败，不静默回空列表
        // （静默回空会把"读不到"当成"无标签"，掩盖存储污染）
        assertThatThrownBy(() -> SemanticTags.fromJson("not-json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SemanticTags.fromJson("{\"tag\":\"金融\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void spaceMembershipThreeStateSemantics() {
        assertThat(SpaceMembership.of("ACTIVE", "MEMBER").isMember()).isTrue();
        assertThat(SpaceMembership.of("ACTIVE", SpaceMembership.ROLE_NONE).isMember()).isFalse();
        assertThat(SpaceMembership.of("FROZEN", "OWNER").isSpaceActive()).isFalse();
        // 不可用态：既非成员也非 ACTIVE（不冒充"非成员/空间不存在"）
        final SpaceMembership unavailable = SpaceMembership.unavailable();
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.isMember()).isFalse();
        assertThat(unavailable.isSpaceActive()).isFalse();
    }

    @Test
    void errorCodeTailIsLastFiveChars() {
        assertThat(CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_IMPORTANT_REJECTED)).isEqualTo("C0003");
        assertThat(CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_FORBIDDEN)).isEqualTo("C0006");
        assertThat(CatalogErrorCodes.tailOf(CatalogErrorCodes.SPACE_SERVICE_UNAVAILABLE)).isEqualTo("S0002");
    }

    // ==== T8 受控词表领域单测（WBS-3.3.3 hifi §5 T8）====

    @Test
    void tagVocabularyRequiresCodeAndName() {
        assertThat(new TagVocabulary("SEMANTIC_TAG", "语义标签受控词表").vocabularyCode())
                .isEqualTo("SEMANTIC_TAG");
        assertThatThrownBy(() -> new TagVocabulary("  ", "语义标签受控词表"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagVocabulary("SEMANTIC_TAG", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagVocabulary(null, "语义标签受控词表"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagTermRequiresWellFormedCodeAndNames() {
        // 编号形态 TT + 4 位（沿业务编号先例；非法形态在构造期即暴露）
        assertThat(new TagTerm("TT0001", "金融", "金融").normalizedTerm()).isEqualTo("金融");
        assertThatThrownBy(() -> new TagTerm("TT001", "金融", "金融"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagTerm("TT00012", "金融", "金融"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagTerm("tt0001", "金融", "金融"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagTerm("TT0001", " ", "金融"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagTerm("TT0001", "金融", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
