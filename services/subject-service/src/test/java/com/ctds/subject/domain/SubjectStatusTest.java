package com.ctds.subject.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 主体状态枚举封闭性单测（WBS-3.1.6 T4，规格行为 4 第 1 条 V1.0 最小集；3.1.2 hifi B7 计划测试项补落）：
 * 状态集合恰为五态且名称固定——增删/改名枚举即红灯，机器锁死"实施包不得私自增删状态"（规格 §6.2）。
 */
class SubjectStatusTest {

    @Test
    void statusSetIsClosedToSpecStateMachine() {
        assertThat(SubjectStatus.values()).containsExactlyInAnyOrder(
                SubjectStatus.PENDING_CERT, SubjectStatus.PENDING_REVIEW, SubjectStatus.ADMITTED,
                SubjectStatus.CERT_FAILED, SubjectStatus.REJECTED);
        assertThat(SubjectStatus.values()).as("状态数锁死为规格五态（行为 4 第 1 条）").hasSize(5);
        assertThatThrownBy(() -> SubjectStatus.valueOf("FROZEN"))
                .as("冻结/注销属 C-1.3 运营态，本规格不可出现（行为 4 边界）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayNamesMatchSpecVocabulary() {
        assertThat(SubjectStatus.PENDING_CERT.getDisplayName()).isEqualTo("待认证");
        assertThat(SubjectStatus.PENDING_REVIEW.getDisplayName()).isEqualTo("待审核");
        assertThat(SubjectStatus.ADMITTED.getDisplayName()).isEqualTo("已入驻");
        assertThat(SubjectStatus.CERT_FAILED.getDisplayName()).isEqualTo("认证失败");
        assertThat(SubjectStatus.REJECTED.getDisplayName()).isEqualTo("已驳回");
    }
}
