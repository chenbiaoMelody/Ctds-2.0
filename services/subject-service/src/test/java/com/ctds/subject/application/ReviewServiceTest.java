package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 审核应用服务单测（WBS-3.1.5 hifi B3~B5 业务分支；HTTP 封套与库表由集成测试覆盖）。
 * 覆盖：通过流转（REVIEWER 触发 + "审核通过"留痕 + 审计）、驳回理由必填/长度上限（配置参数）、
 * 驳回流转（理由入留痕备注）、申请编号不存在同形出站；并发乐观门槛由仓储层保证（集成测试断言）。
 */
class ReviewServiceTest {

    private static final String SUBJECT_NO = "S20260913000901";
    private static final String REVIEWER = "reviewer-01";

    private SubjectRepository subjectRepository;
    private SubjectStatusService statusService;
    private SubjectOpsSupport ops;
    private CertificationProperties properties;
    private ReviewService service;
    private Subject subject;

    @BeforeEach
    void setUp() {
        subjectRepository = mock(SubjectRepository.class);
        statusService = mock(SubjectStatusService.class);
        ops = mock(SubjectOpsSupport.class);
        properties = new CertificationProperties();
        service = new ReviewService(subjectRepository, statusService, ops, properties);
        subject = new Subject(1L, SUBJECT_NO, "演示公司", "91330100MA27XW123X", SubjectType.ENTERPRISE,
                "杭州市XX区XX路88号", "张三", "13800001234", "admin001", "applicant-01",
                SubjectStatus.PENDING_REVIEW, LocalDateTime.now(), LocalDateTime.now());
        when(ops.operator()).thenReturn(REVIEWER);
    }

    @Test
    void approveTransitionsToAdmittedWithReviewerTrailAndAudit() {
        when(ops.requireSubject(SUBJECT_NO)).thenReturn(subject);

        final ReviewActionResult result = service.approve(SUBJECT_NO);

        assertThat(result.subjectNo()).isEqualTo(SUBJECT_NO);
        assertThat(result.status()).isEqualTo("ADMITTED");
        verify(statusService).transition(eq(1L), eq(SubjectStatus.PENDING_REVIEW),
                eq(SubjectStatus.ADMITTED), eq(TriggerRole.REVIEWER), eq(REVIEWER), eq("审核通过"));
        verify(ops).audit(eq(REVIEWER), eq(ReviewService.ACTION_REVIEW_APPROVE),
                eq(SUBJECT_NO), eq(AuditOutcome.SUCCESS), isNull());
    }

    @Test
    void approveUnknownSubjectNoThrowsSameShapeAsNotFound() {
        // 1000C0003 映射本体随 WBS-3.1.6 S1 上收（断言在 SubjectOpsSupportTest）；本用例锚定审核服务如实透传

        when(ops.requireSubject(SUBJECT_NO))
                .thenThrow(new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
        assertThatThrownBy(() -> service.approve(SUBJECT_NO))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().value())
                .isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND.value());
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void approveOnAdmittedStateRejectedByGateWithDeniedAudit() {
        // 3.1.4 教训固化：状态门槛必须有拒绝态负向用例——删掉 requirePendingReview 本用例必红
        final Subject admitted = new Subject(1L, SUBJECT_NO, "演示公司", "91330100MA27XW123X",
                SubjectType.ENTERPRISE, "杭州市XX区XX路88号", "张三", "13800001234", "admin001", "applicant-01",
                SubjectStatus.ADMITTED, LocalDateTime.now(), LocalDateTime.now());
        when(ops.requireSubject(SUBJECT_NO)).thenReturn(admitted);

        assertThatThrownBy(() -> service.approve(SUBJECT_NO))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().value())
                .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED.value());
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), anyString(), anyString());
        verify(ops).audit(eq(REVIEWER), eq(ReviewService.ACTION_REVIEW_APPROVE),
                eq(SUBJECT_NO), eq(AuditOutcome.DENIED), eq("state_not_allowed"));
    }

    @Test
    void rejectOnRejectedStateRejectedByGateWithDeniedAudit() {
        // 已驳回态（B5 第四态）同样被门槛拒绝——非待审核四态共用同一前置门槛
        final Subject rejected = new Subject(1L, SUBJECT_NO, "演示公司", "91330100MA27XW123X",
                SubjectType.ENTERPRISE, "杭州市XX区XX路88号", "张三", "13800001234", "admin001", "applicant-01",
                SubjectStatus.REJECTED, LocalDateTime.now(), LocalDateTime.now());
        when(ops.requireSubject(SUBJECT_NO)).thenReturn(rejected);

        assertThatThrownBy(() -> service.reject(SUBJECT_NO, "再次驳回"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().value())
                .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED.value());
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), anyString(), anyString());
        verify(ops).audit(eq(REVIEWER), eq(ReviewService.ACTION_REVIEW_REJECT),
                eq(SUBJECT_NO), eq(AuditOutcome.DENIED), eq("state_not_allowed"));
    }

    @Test
    void rejectTransitionsToRejectedWithReasonRemarkAndAudit() {
        when(ops.requireSubject(SUBJECT_NO)).thenReturn(subject);

        final ReviewActionResult result = service.reject(SUBJECT_NO, "证照材料不齐全");

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(statusService).transition(eq(1L), eq(SubjectStatus.PENDING_REVIEW),
                eq(SubjectStatus.REJECTED), eq(TriggerRole.REVIEWER), eq(REVIEWER), eq("审核驳回：证照材料不齐全"));
        verify(ops).audit(eq(REVIEWER), eq(ReviewService.ACTION_REVIEW_REJECT),
                eq(SUBJECT_NO), eq(AuditOutcome.SUCCESS), isNull());
    }

    @Test
    void rejectBlankReasonRejectedBeforeLookup() {
        assertThatThrownBy(() -> service.reject(SUBJECT_NO, "   "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回理由必填");
        assertThatThrownBy(() -> service.reject(SUBJECT_NO, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回理由必填");
        verify(ops, never()).requireSubject(anyString());
    }

    @Test
    void rejectOverlongReasonRejectedAgainstConfiguredLimit() {
        properties.setReviewReasonMaxLength(200);
        final String overlong = "驳".repeat(201);

        assertThatThrownBy(() -> service.reject(SUBJECT_NO, overlong))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("200");
        verify(ops, never()).requireSubject(anyString());
    }

    @Test
    void queueMapsPendingReviewPageToItems() {
        final PageQuery query = PageQuery.of(1, 10, null);
        when(subjectRepository.countByStatus(SubjectStatus.PENDING_REVIEW)).thenReturn(1L);
        when(subjectRepository.findByStatus(SubjectStatus.PENDING_REVIEW, 0, 10))
                .thenReturn(List.of(subject));

        final PageResult<ReviewQueueItem> page = service.queue(query);

        assertThat(page.total()).isEqualTo(1);
        final ReviewQueueItem item = page.list().get(0);
        assertThat(item.subjectNo()).isEqualTo(SUBJECT_NO);
        assertThat(item.subjectName()).isEqualTo("演示公司");
        assertThat(item.subjectType()).isEqualTo("ENTERPRISE");
        assertThat(item.createdAt()).isEqualTo(subject.createdAt());
    }

    @Test
    void queueReturnsEmptyPageWhenNoPendingReview() {
        final PageQuery query = PageQuery.of(1, 10, null);
        when(subjectRepository.countByStatus(SubjectStatus.PENDING_REVIEW)).thenReturn(0L);

        final PageResult<ReviewQueueItem> page = service.queue(query);

        assertThat(page.total()).isZero();
        assertThat(page.list()).isEmpty();
    }
}
