package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ctds.common.logging.AuditRecorder;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
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
        service = new SubjectRegistrationService(repository, statusService, auditRecorder, ownershipGuard);
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
