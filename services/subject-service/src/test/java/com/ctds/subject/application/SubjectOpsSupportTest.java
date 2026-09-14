package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 公共支撑单测：文件名归一化（WBS-3.1.5 hifi B8①，3.1.4 观察项处置）控制字符剥除 / 首尾空白去除 /
 * 超长截断三例 + null 透传（透传后由既有文件校验拒绝）；requireSubject 上收映射口径
 * （WBS-3.1.6 S1，原两服务私有行为的归宿断言——格式错 1000C0001 / 不存在 1000C0003 同形防探测）。
 */
class SubjectOpsSupportTest {

    private static final String SUBJECT_NO = "S20260913000901";

    private final SubjectOpsSupport ops = new SubjectOpsSupport(null, null);

    @Test
    void requireSubjectRejectsMalformedNumberWithoutDbHit() {
        final SubjectRepository repository = mock(SubjectRepository.class);
        final SubjectOpsSupport withRepo = new SubjectOpsSupport(null, repository);

        assertThatThrownBy(() -> withRepo.requireSubject("bad-no"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().value())
                .isEqualTo(ErrorCodes.PARAM_INVALID.value());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void requireSubjectMissingThrowsResourceNotFoundFixedMessage() {
        final SubjectRepository repository = mock(SubjectRepository.class);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.empty());
        final SubjectOpsSupport withRepo = new SubjectOpsSupport(null, repository);

        assertThatThrownBy(() -> withRepo.requireSubject(SUBJECT_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("申请编号不存在")
                .extracting(e -> ((BizException) e).getErrorCode().value())
                .isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND.value());
    }

    @Test
    void requireSubjectReturnsFoundSubject() {
        final Subject subject = mock(Subject.class);
        final SubjectRepository repository = mock(SubjectRepository.class);
        when(repository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(subject));
        final SubjectOpsSupport withRepo = new SubjectOpsSupport(null, repository);

        assertThat(withRepo.requireSubject(SUBJECT_NO)).isSameAs(subject);
    }

    @Test
    void stripsControlCharactersFromFileName() {
        assertThat(ops.normalizeFileName("A1\u0001\u0007.jpg")).isEqualTo("A1.jpg");
        assertThat(ops.normalizeFileName("证照\u001F影像.jpg")).isEqualTo("证照影像.jpg");
        assertThat(ops.normalizeFileName("cert\u007Ffile.cer")).isEqualTo("certfile.cer");
    }

    @Test
    void trimsOuterWhitespaceAndKeepsInnerSpaces() {
        assertThat(ops.normalizeFileName("  a1 .jpg ")).isEqualTo("a1 .jpg");
        assertThat(ops.normalizeFileName("\tA3.cer\n")).isEqualTo("A3.cer");
    }

    @Test
    void truncatesToMaxLength() {
        final String overlong = "长".repeat(300) + ".jpg";
        final String normalized = ops.normalizeFileName(overlong);
        assertThat(normalized).hasSize(255);
        assertThat(ops.normalizeFileName("short.jpg")).isEqualTo("short.jpg");
    }

    @Test
    void nullAndEmptyPassThroughForDownstreamValidation() {
        assertThat(ops.normalizeFileName(null)).isNull();
        assertThat(ops.normalizeFileName("   ")).isEmpty();
    }
}
