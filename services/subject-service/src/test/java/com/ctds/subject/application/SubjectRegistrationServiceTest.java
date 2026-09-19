package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 注册应用服务单测（hifi B3/B5/B6 业务规则分支；HTTP 封套与幂等并发由集成测试覆盖）。
 */
class SubjectRegistrationServiceTest {

    private static final String USCC = "91330100MA27X8ABCD";
    private static final String SUBJECT_NO = "S20260913000001";

    private SubjectRepository repository;
    private SubjectStatusService statusService;
    private AuditRecorder auditRecorder;
    private OwnershipGuard ownershipGuard;
    private SubjectRegistrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubjectRepository.class);
        statusService = mock(SubjectStatusService.class);
        auditRecorder = mock(AuditRecorder.class);
        ownershipGuard = mock(OwnershipGuard.class);
        service = new SubjectRegistrationService(repository, statusService,
                new SubjectOpsSupport(auditRecorder, repository), ownershipGuard, Clock.systemDefaultZone());
    }

    @Test
    void newRegistrationCreatesSubjectWithApplicationNo() {
        when(repository.findByUscc(USCC)).thenReturn(Optional.empty());
        when(repository.nextDailySeq(any())).thenReturn(1);

        final RegistrationResult result = service.register(command("示例数据科技有限公司"));

        assertThat(result.subjectNo()).matches("S\\d{8}\\d{6}");
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_CERT);
        final ArgumentCaptor<Subject> subject = ArgumentCaptor.forClass(Subject.class);
        final ArgumentCaptor<StatusTransition> transition = ArgumentCaptor.forClass(StatusTransition.class);
        verify(repository).create(subject.capture(), transition.capture());
        assertThat(subject.getValue().uscc()).isEqualTo(USCC);
        assertThat(subject.getValue().status()).isEqualTo(SubjectStatus.PENDING_CERT);
        assertThat(transition.getValue().fromStatus()).isNull();
        assertThat(transition.getValue().toStatus()).isEqualTo(SubjectStatus.PENDING_CERT);
        assertThat(transition.getValue().triggerRole()).isEqualTo(TriggerRole.APPLICANT);
        verify(auditRecorder).record(any(AuditEvent.class));
    }

    @Test
    void duplicateActiveSubjectIsRejectedWithoutLeakingInfo() {
        when(repository.findByUscc(USCC)).thenReturn(Optional.of(subject(SubjectStatus.PENDING_CERT)));
        when(repository.findLatestTransition(anyLong())).thenReturn(Optional.of(registerTransition()));

        assertThatThrownBy(() -> service.register(command("任意名称")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(SubjectErrorCodes.SUBJECT_ALREADY_REGISTERED))
                .hasMessage("该主体已注册");
        verify(repository, never()).create(any(), any());
    }

    @Test
    void invalidUsccIsRejectedBeforeAnyPersistence() {
        final RegisterCommand bad = new RegisterCommand("示例数据科技有限公司", "BAD-USCC", "ENTERPRISE",
                "杭州市XX区XX路88号", "张三", "13800001234", "admin001");

        assertThatThrownBy(() -> service.register(bad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("统一社会信用代码格式不正确");
        verify(repository, never()).create(any(), any());
    }

    @Test
    void missingFieldsReportAllProblemsJoined() {
        final RegisterCommand bad = new RegisterCommand(null, null, "WRONG", null, null, null, null);

        assertThatThrownBy(() -> service.register(bad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主体名称不能为空")
                .hasMessageContaining("统一社会信用代码格式不正确")
                .hasMessageContaining("主体类型不合法")
                .hasMessageContaining("注册地址不能为空")
                .hasMessageContaining("联系人姓名不能为空")
                .hasMessageContaining("联系电话不能为空")
                .hasMessageContaining("管理员账号不合法");
    }

    @Test
    void rejectedSubjectResubmitsReusingSameRow() {
        final Subject rejected = subject(SubjectStatus.REJECTED);
        when(repository.findByUscc(USCC)).thenReturn(Optional.of(rejected));
        // 重报请求携带不同主体类型：主体类型不在 hifi 重报可变字段集合内，必须保留原值
        final RegisterCommand command = new RegisterCommand("修改后的名称", USCC, "GOV",
                "杭州市XX区XX路88号", "张三", "13800001234", "admin001");

        final RegistrationResult result = service.register(command);

        assertThat(result.subjectNo()).isEqualTo(rejected.subjectNo());
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_CERT);
        final ArgumentCaptor<Subject> subject = ArgumentCaptor.forClass(Subject.class);
        final ArgumentCaptor<StatusTransition> transition = ArgumentCaptor.forClass(StatusTransition.class);
        verify(repository).resubmit(subject.capture(), transition.capture());
        assertThat(subject.getValue().subjectType()).isEqualTo(SubjectType.ENTERPRISE);
        assertThat(subject.getValue().subjectName()).isEqualTo("修改后的名称");
        assertThat(transition.getValue().fromStatus()).isEqualTo(SubjectStatus.REJECTED);
        assertThat(transition.getValue().toStatus()).isEqualTo(SubjectStatus.PENDING_CERT);
        assertThat(transition.getValue().remark())
                .isEqualTo(SubjectStatusService.RESUBMIT_AFTER_REJECT_REMARK);
    }

    @Test
    void cancelledPendingSubjectResubmitsReusingSameRow() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findByUscc(USCC)).thenReturn(Optional.of(pending));
        when(repository.findLatestTransition(pending.id()))
                .thenReturn(Optional.of(new StatusTransition(SubjectStatus.PENDING_CERT, SubjectStatus.PENDING_CERT,
                        TriggerRole.APPLICANT, "applicant-01", SubjectStatusService.CANCEL_REMARK,
                        LocalDateTime.now())));

        final RegistrationResult result = service.register(command("撤销后重新提交的名称"));

        assertThat(result.subjectNo()).isEqualTo(pending.subjectNo());
        final ArgumentCaptor<StatusTransition> transition = ArgumentCaptor.forClass(StatusTransition.class);
        verify(repository).resubmit(any(Subject.class), transition.capture());
        assertThat(transition.getValue().remark())
                .isEqualTo(SubjectStatusService.RESUBMIT_AFTER_CANCEL_REMARK);
    }

    @Test
    void cancelUnknownSubjectNoIsResourceNotFound() {
        when(repository.findBySubjectNo("S20260913000099")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("S20260913000099"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND));
    }

    @Test
    void cancelNonPendingSubjectIsRejected() {
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(subject(SubjectStatus.ADMITTED)));

        assertThatThrownBy(() -> service.cancel(SUBJECT_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(SubjectErrorCodes.SUBJECT_CANCEL_NOT_ALLOWED))
                .hasMessage("当前状态不可撤销");
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelPendingSubjectAppendsCancelTransition() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(pending));
        when(repository.findLatestTransition(pending.id())).thenReturn(Optional.of(registerTransition()));

        final CancellationResult result = service.cancel(SUBJECT_NO);

        assertThat(result.cancelled()).isTrue();
        verify(statusService).transition(pending.id(), SubjectStatus.PENDING_CERT, SubjectStatus.PENDING_CERT,
                TriggerRole.APPLICANT, "anonymous", SubjectStatusService.CANCEL_REMARK);
    }

    @Test
    void detailReturnsSubjectWithAllTransitions() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(pending));
        final List<StatusTransition> logs = List.of(registerTransition());
        when(repository.findTransitions(pending.id())).thenReturn(logs);

        final SubjectDetail detail = service.detail(SUBJECT_NO);

        assertThat(detail.subject()).isEqualTo(pending);
        assertThat(detail.transitions()).isEqualTo(logs);
    }

    // ==== CHG-C-1.1-V1.2：联系电话双格式（规格行为 1 第 6 条，hifi §2 规则表） ====

    @Test
    void validPhoneFormatsPassValidation() {
        final String[] validPhones = {"13800001234", "0571-87654321", "010-12345678", "0571-1234567"};
        for (final String phone : validPhones) {
            final RegisterCommand command = new RegisterCommand("示例数据科技有限公司", USCC, "ENTERPRISE",
                    "杭州市XX区XX路88号", "张三", phone, "admin001");

            assertThatCode(() -> service.register(command)).doesNotThrowAnyException();
        }
    }

    @Test
    void invalidPhoneFormatsAreRejectedWithFormatMessage() {
        final String[] invalidPhones = {"12345678901", "1380000123", "057187654321", "测试电话",
                "0571-87654321#8001"};
        for (final String phone : invalidPhones) {
            final RegisterCommand bad = new RegisterCommand("示例数据科技有限公司", USCC, "ENTERPRISE",
                    "杭州市XX区XX路88号", "张三", phone, "admin001");

            assertThatThrownBy(() -> service.register(bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("联系电话格式不正确（手机 11 位或 区号-座机）");
        }
        verify(repository, never()).create(any(), any());
    }

    /** 管理员账号规则 V1.0 既有（规格 V1.2 第 7 条补写），本用例锁定边界防回归（hifi §5）。 */
    @Test
    void adminAccountBoundaryLengthsAreLockedByExistingRule() {
        when(repository.findByUscc(USCC)).thenReturn(Optional.empty());
        when(repository.nextDailySeq(any())).thenReturn(1);

        assertThatCode(() -> service.register(new RegisterCommand("示例数据科技有限公司", USCC, "ENTERPRISE",
                "杭州市XX区XX路88号", "张三", "13800001234", "a".repeat(64))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.register(new RegisterCommand("示例数据科技有限公司", USCC, "ENTERPRISE",
                "杭州市XX区XX路88号", "张三", "13800001234", "a".repeat(65))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员账号不合法");
        assertThatThrownBy(() -> service.register(new RegisterCommand("示例数据科技有限公司", USCC, "ENTERPRISE",
                "杭州市XX区XX路88号", "张三", "13800001234", "管理员账号")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员账号不合法");
    }

    // ==== CHG-C-1.1-V1.2：入驻进度自助查询（规格行为 8；凭证断言取代归属断言，hifi §3） ====

    @Test
    void progressMatchingUsccReturnsMinimalFieldsWithoutSensitiveData() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(pending));

        final ProgressResult result = service.progress(SUBJECT_NO, USCC);

        assertThat(result.subjectNo()).isEqualTo(SUBJECT_NO);
        assertThat(result.subjectName()).isEqualTo("原主体名称");
        assertThat(result.subjectType()).isEqualTo(SubjectType.ENTERPRISE);
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_CERT);
        assertThat(result.rejectReason()).isNull();
        final ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("subject.progress_query");
        assertThat(audit.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    void progressNormalizesLowercaseAndWhitespaceUscc() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(pending));

        final ProgressResult result = service.progress(SUBJECT_NO, " " + USCC.toLowerCase() + " ");

        assertThat(result.subjectNo()).isEqualTo(SUBJECT_NO);
    }

    @Test
    void progressMalformedUsccIsParamInvalidWithoutDbAccess() {
        assertThatThrownBy(() -> service.progress(SUBJECT_NO, "91330100MA27X8ABC"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        verify(repository, never()).findBySubjectNo(any());
    }

    @Test
    void progressUnknownSubjectNoIsUniformNotFoundWithoutDeniedAudit() {
        when(repository.findBySubjectNo("S20260913000099")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.progress("S20260913000099", USCC))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND))
                .hasMessage("未查询到匹配的申请");
        verify(auditRecorder, never()).record(any(AuditEvent.class));
    }

    @Test
    void progressUsccMismatchIsDeniedAndAuditedWithUniformMessage() {
        final Subject pending = subject(SubjectStatus.PENDING_CERT);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.progress(SUBJECT_NO, "91330100MA27X8ABEF"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND))
                .hasMessage("未查询到匹配的申请");
        final ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(audit.getValue().detail()).containsEntry("reason", "progress_denied");
    }

    @Test
    void progressRejectedSubjectReturnsLatestRejectRemark() {
        final Subject rejected = subject(SubjectStatus.REJECTED);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(rejected));
        when(repository.findLatestTransition(rejected.id())).thenReturn(Optional.of(
                new StatusTransition(SubjectStatus.PENDING_REVIEW, SubjectStatus.REJECTED,
                        TriggerRole.REVIEWER, "reviewer-01", "材料不齐全，予以驳回", LocalDateTime.now())));

        final ProgressResult result = service.progress(SUBJECT_NO, USCC);

        assertThat(result.status()).isEqualTo(SubjectStatus.REJECTED);
        assertThat(result.rejectReason()).isEqualTo("材料不齐全，予以驳回");
    }

    private RegisterCommand command(final String subjectName) {
        return new RegisterCommand(subjectName, USCC, "ENTERPRISE", "杭州市XX区XX路88号", "张三",
                "13800001234", "admin001");
    }

    private Subject subject(final SubjectStatus status) {
        return new Subject(7L, SUBJECT_NO, "原主体名称", USCC, SubjectType.ENTERPRISE, "原注册地址", "李四",
                "13900005678", "old-admin", "applicant-01", status,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }

    private StatusTransition registerTransition() {
        return new StatusTransition(null, SubjectStatus.PENDING_CERT, TriggerRole.APPLICANT, "applicant-01",
                null, LocalDateTime.now().minusHours(1));
    }
}
