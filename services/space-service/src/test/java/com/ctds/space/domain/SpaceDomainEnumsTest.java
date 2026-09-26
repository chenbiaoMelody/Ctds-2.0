package com.ctds.space.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 空间域 12 枚举封闭性单测（WBS-3.2.2 任务卡 §三 行为 4"枚举值域断言"落点，评审修复批补齐）：
 * 值域集合恰为规格/hifi §1 定稿且显示名固定——增删/改名枚举即红灯，机器锁死"实施包不得私自增删值域"。
 * 风格沿 subject-service SubjectStatusTest 先例；DB 不建 CHECK（沿 subject 先例），值域由枚举类+本测试保证。
 */
class SpaceDomainEnumsTest {

    @Test
    void spaceStatusIsClosedToSpecStateMachine() {
        assertThat(SpaceStatus.values()).containsExactlyInAnyOrder(
                SpaceStatus.CREATED, SpaceStatus.ACTIVE, SpaceStatus.FROZEN, SpaceStatus.DISSOLVED);
        assertThat(SpaceStatus.values()).as("空间状态数锁死为规格四态（行为 2）").hasSize(4);
        assertThatThrownBy(() -> SpaceStatus.valueOf("ARCHIVED"))
                .as("ARCHIVED 属策略条目态，空间状态机不含（行为 2）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SpaceStatus.CREATED.getDisplayName()).isEqualTo("已创建");
        assertThat(SpaceStatus.ACTIVE.getDisplayName()).isEqualTo("已启用");
        assertThat(SpaceStatus.FROZEN.getDisplayName()).isEqualTo("已冻结");
        assertThat(SpaceStatus.DISSOLVED.getDisplayName()).isEqualTo("已解散");
    }

    @Test
    void accessModeIsClosedToSpecThreeTiers() {
        assertThat(AccessMode.values()).containsExactlyInAnyOrder(
                AccessMode.OPEN, AccessMode.INVITE, AccessMode.APPROVAL);
        assertThat(AccessMode.values()).as("参与方范围锁死为规格三档（行为 1）").hasSize(3);
        assertThat(AccessMode.OPEN.getDisplayName()).isEqualTo("公开");
        assertThat(AccessMode.INVITE.getDisplayName()).isEqualTo("邀请制");
        assertThat(AccessMode.APPROVAL.getDisplayName()).isEqualTo("审批制");
    }

    @Test
    void visibilityIsClosedToSpecTwoTiers() {
        assertThat(Visibility.values()).containsExactlyInAnyOrder(Visibility.PUBLIC, Visibility.PRIVATE);
        assertThat(Visibility.values()).as("可见性锁死为规格两档（行为 1）").hasSize(2);
        assertThat(Visibility.PUBLIC.getDisplayName()).isEqualTo("公开");
        assertThat(Visibility.PRIVATE.getDisplayName()).isEqualTo("不公开");
    }

    @Test
    void sceneTypeIsClosedToConfirmedThreeTiers() {
        assertThat(SceneType.values()).containsExactlyInAnyOrder(
                SceneType.FINTECH, SceneType.MEDICAL, SceneType.OTHER);
        assertThat(SceneType.values()).as("场景类型锁死为 Q8-A 确认三值，扩充须走变更流程").hasSize(3);
        assertThat(SceneType.FINTECH.getDisplayName()).isEqualTo("普惠金融");
        assertThat(SceneType.MEDICAL.getDisplayName()).isEqualTo("医疗验证");
        assertThat(SceneType.OTHER.getDisplayName()).isEqualTo("其他");
    }

    @Test
    void memberRoleIsClosedToSpecThreeTiers() {
        assertThat(MemberRole.values()).containsExactlyInAnyOrder(
                MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MEMBER);
        assertThat(MemberRole.values()).as("角色锁死为规格三档（行为 4；只读审计角色归 C-9 不建）").hasSize(3);
        assertThat(MemberRole.OWNER.getDisplayName()).isEqualTo("所有者");
        assertThat(MemberRole.ADMIN.getDisplayName()).isEqualTo("管理员");
        assertThat(MemberRole.MEMBER.getDisplayName()).isEqualTo("成员");
    }

    @Test
    void memberStatusIsClosedToSpecStateMachine() {
        assertThat(MemberStatus.values()).containsExactlyInAnyOrder(
                MemberStatus.ACTIVE, MemberStatus.LEFT, MemberStatus.REMOVED);
        assertThat(MemberStatus.values()).as("成员关系状态数锁死为规格三态（行为 5）").hasSize(3);
        assertThatThrownBy(() -> MemberStatus.valueOf("PENDING"))
                .as("待确认属准入单态，成员关系不含（行为 3/5 两表分工）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MemberStatus.ACTIVE.getDisplayName()).isEqualTo("生效中");
        assertThat(MemberStatus.LEFT.getDisplayName()).isEqualTo("已退出");
        assertThat(MemberStatus.REMOVED.getDisplayName()).isEqualTo("已移除");
    }

    @Test
    void admissionTypeIsClosedToSpecTwoForms() {
        assertThat(AdmissionType.values()).containsExactlyInAnyOrder(
                AdmissionType.APPLICATION, AdmissionType.INVITATION);
        assertThat(AdmissionType.values()).as("准入形态锁死为申请/邀请两值（行为 3）").hasSize(2);
        assertThat(AdmissionType.APPLICATION.getDisplayName()).isEqualTo("申请");
        assertThat(AdmissionType.INVITATION.getDisplayName()).isEqualTo("邀请");
    }

    @Test
    void admissionStatusIsClosedToSpecStateMachine() {
        assertThat(AdmissionStatus.values()).containsExactlyInAnyOrder(
                AdmissionStatus.PENDING_APPROVAL, AdmissionStatus.PENDING_CONFIRMATION,
                AdmissionStatus.APPROVED, AdmissionStatus.REJECTED, AdmissionStatus.DECLINED,
                AdmissionStatus.CANCELLED);
        assertThat(AdmissionStatus.values()).as("准入单状态数锁死为规格六态（行为 3）").hasSize(6);
        assertThatThrownBy(() -> AdmissionStatus.valueOf("ACTIVE"))
                .as("生效中属成员关系态，准入单不含（行为 3/5 两表分工）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AdmissionStatus.PENDING_APPROVAL.getDisplayName()).isEqualTo("待审批");
        assertThat(AdmissionStatus.PENDING_CONFIRMATION.getDisplayName()).isEqualTo("待被邀方确认");
        assertThat(AdmissionStatus.APPROVED.getDisplayName()).isEqualTo("已通过");
        assertThat(AdmissionStatus.REJECTED.getDisplayName()).isEqualTo("已拒绝");
        assertThat(AdmissionStatus.DECLINED.getDisplayName()).isEqualTo("被邀方谢绝");
        assertThat(AdmissionStatus.CANCELLED.getDisplayName()).isEqualTo("已撤回");
    }

    @Test
    void policyScopeIsClosedToSpecTwoScopes() {
        assertThat(PolicyScope.values()).containsExactlyInAnyOrder(
                PolicyScope.PLATFORM, PolicyScope.SPACE);
        assertThat(PolicyScope.values()).as("策略作用域锁死为平台/空间两值（行为 7）").hasSize(2);
        assertThat(PolicyScope.PLATFORM.getDisplayName()).isEqualTo("平台级");
        assertThat(PolicyScope.SPACE.getDisplayName()).isEqualTo("空间级");
    }

    @Test
    void policyStatusIsClosedToSpecStateMachine() {
        assertThat(PolicyStatus.values()).containsExactlyInAnyOrder(
                PolicyStatus.ACTIVE, PolicyStatus.ARCHIVED);
        assertThat(PolicyStatus.values()).as("策略条目状态锁死为生效/归档两值（行为 7 规则 5）").hasSize(2);
        assertThatThrownBy(() -> PolicyStatus.valueOf("DRAFT"))
                .as("草稿态不在契约内：条目变更走留痕，本表只存当前值")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PolicyStatus.ACTIVE.getDisplayName()).isEqualTo("生效");
        assertThat(PolicyStatus.ARCHIVED.getDisplayName()).isEqualTo("归档");
    }

    @Test
    void targetTypeIsClosedToSpecFourKinds() {
        assertThat(TargetType.values()).containsExactlyInAnyOrder(
                TargetType.SPACE, TargetType.MEMBER, TargetType.ADMISSION, TargetType.POLICY);
        assertThat(TargetType.values()).as("留痕对象类型锁死为四值（行为 6）").hasSize(4);
        assertThat(TargetType.SPACE.getDisplayName()).isEqualTo("空间");
        assertThat(TargetType.MEMBER.getDisplayName()).isEqualTo("成员");
        assertThat(TargetType.ADMISSION.getDisplayName()).isEqualTo("准入单");
        assertThat(TargetType.POLICY.getDisplayName()).isEqualTo("策略条目");
    }

    @Test
    void actionResultIsClosedToSpecTwoOutcomes() {
        assertThat(ActionResult.values()).containsExactlyInAnyOrder(
                ActionResult.SUCCESS, ActionResult.DENIED);
        assertThat(ActionResult.values()).as("留痕结果锁死为成功/被拒绝两值（拒绝同样留痕，行为 6 规则 5）").hasSize(2);
        assertThat(ActionResult.SUCCESS.getDisplayName()).isEqualTo("成功");
        assertThat(ActionResult.DENIED.getDisplayName()).isEqualTo("被拒绝");
    }
}
