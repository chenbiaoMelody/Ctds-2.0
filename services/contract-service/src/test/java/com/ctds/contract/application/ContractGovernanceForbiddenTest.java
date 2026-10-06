package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthUser;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.DealTextCipher;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.SubjectAdmissionPort;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * W12 治理越权腿（C0011）应用层直调测试（hifi §2.3 权限注、§3 1008C0011；规格行为 7 规则 4）。
 *
 * <p>该腿在生产配置下由注解层 {@code @RequirePermission("contract.governance")} 先行挡住
 * （provider 档不持该权限点 → 注解层 403、零留痕，见 §权限注），因此 HTTP 面**不可达**；
 * 本类以直调应用服务 + 注入身份的方式覆盖应用层第二道判定，断言
 * ① 非 admin → 1008C0011 且**零查库**（不构成合约存在性枚举通道）；② 拒绝留痕 DENIED_ACCESS
 * 尾号 = C0011；③ admin（注入角色）= 越不过理由必填门槛（理由缺失 → 1008C0008）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContractGovernanceForbiddenTest {

    private static final String CONTRACT_NO = "CO000123";
    private static final String OPERATOR = "S-prov";

    @Mock
    private ContractRepository repository;
    @Mock
    private TemplateQueryService templateQueryService;
    @Mock
    private SubjectAdmissionPort subjectAdmissionPort;
    @Mock
    private CatalogProductPort catalogProductPort;
    @Mock
    private DidPort didPort;
    @Mock
    private DealTextCipher dealTextCipher;
    @Mock
    private Sm3Service sm3Service;

    private ContractCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new ContractCommandService(repository, templateQueryService,
                subjectAdmissionPort, catalogProductPort, didPort, dealTextCipher, sm3Service,
                Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void nonAdminForceTerminationIsRejectedWithC0011DeniedLogAndNoExistenceProbe()
            throws Exception {
        injectIdentity(OPERATOR, Set.of("provider"));
        assertThatThrownBy(() -> commandService.forceTerminate(CONTRACT_NO, OPERATOR, "越权尝试"))
                .isInstanceOfSatisfying(ContractBizException.class, ex -> assertThat(
                        ex.getErrorCode().value()).isEqualTo("1008C0011"));
        // 拒绝留痕（DENIED_ACCESS + 理由码尾号 C0011 + 操作者）
        final ArgumentCaptor<ContractActionLog> log = ArgumentCaptor.forClass(
                ContractActionLog.class);
        verify(repository).insertLog(log.capture());
        assertThat(log.getValue().action()).isEqualTo(ContractAction.DENIED_ACCESS);
        assertThat(log.getValue().reasonCode()).isEqualTo("C0011");
        assertThat(log.getValue().actorSubjectNo()).isEqualTo(OPERATOR);
        assertThat(log.getValue().contractNo()).isEqualTo(CONTRACT_NO);
        // 零查库：非 admin 分支不得触碰合约存在性（防枚举）
        verify(repository, never()).findByNo(any());
    }

    @Test
    void adminForceTerminationStillEnforcesMandatoryReason() throws Exception {
        injectIdentity("S-admin", Set.of("admin"));
        assertThatThrownBy(() -> commandService.forceTerminate(CONTRACT_NO, "S-admin", "  "))
                .isInstanceOfSatisfying(ContractBizException.class, ex -> assertThat(
                        ex.getErrorCode().value()).isEqualTo("1008C0008"));
        // 理由门槛先生效：未进入任何状态转移
        verify(repository, never()).terminate(any());
    }

    /** 以反射注入身份（AuthContext.set 为包内可见；沿服务测试"请求线程身份"口径）。 */
    private static void injectIdentity(final String subject, final Set<String> roles)
            throws Exception {
        final Method set = AuthContext.class.getDeclaredMethod("set", AuthUser.class);
        set.setAccessible(true);
        set.invoke(null, new AuthUser(subject, roles));
    }
}
