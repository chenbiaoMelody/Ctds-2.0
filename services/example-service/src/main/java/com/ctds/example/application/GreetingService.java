package com.ctds.example.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.common.logging.LogContext;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.example.domain.Greeting;
import com.ctds.example.domain.GreetingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 应用服务：编排领域对象与仓储，禁止依赖接口层（章程 4.3）。
 * 内存实现的排序为演示用途；正式模块的排序在持久层执行（组件只负责参数契约）。
 * 审计埋点：创建成功记 SUCCESS；排序字段白名单拒绝记 DENIED（策略生效+绕过被拒）。
 */
@Service
public class GreetingService {

    private static final Logger log = LoggerFactory.getLogger(GreetingService.class);

    private final GreetingRepository greetingRepository;
    private final AuditRecorder auditRecorder;

    public GreetingService(final GreetingRepository greetingRepository,
            final AuditRecorder auditRecorder) {
        this.greetingRepository = greetingRepository;
        this.auditRecorder = auditRecorder;
    }

    public Greeting greet(final String message) {
        final Greeting saved = greetingRepository.save(Greeting.of(message));
        auditRecorder.record(AuditEvent.of("anonymous", "greeting.create", "greeting",
                saved.id().toString(), AuditOutcome.SUCCESS, null));
        return saved;
    }

    public PageResult<Greeting> list(final PageQuery query) {
        final List<Greeting> sorted = applySort(greetingRepository.findAll(), query);
        final int from = (int) Math.min(query.offset(), sorted.size());
        final int to = Math.min(from + query.pageSize(), sorted.size());
        return PageResult.of(sorted.subList(from, to), sorted.size(), query);
    }

    /**
     * 删除问候语（演示鉴权接入 B9）：成功记 SUCCESS 审计，actor 用真实身份
     * （兑现 2.4.4 "鉴权组件就绪后接真实身份" 预留）；不存在按 1000C0003 拒绝。
     */
    public void delete(final UUID id) {
        if (!greetingRepository.deleteById(id)) {
            throw new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "资源不存在");
        }
        auditRecorder.record(AuditEvent.of(AuthContext.subject(), "greeting.delete", "greeting",
                id.toString(), AuditOutcome.SUCCESS, null));
    }

    private List<Greeting> applySort(final List<Greeting> all, final PageQuery query) {
        if (query.sortField() == null) {
            return all;
        }
        final Comparator<Greeting> comparator = switch (query.sortField()) {
            case "message" -> Comparator.comparing(Greeting::message);
            case "id" -> Comparator.comparing(Greeting::id);
            default -> {
                LogContext.setErrorCode(ErrorCodes.PARAM_INVALID.value());
                log.warn("list denied: unsupported sort field");
                auditRecorder.record(AuditEvent.of("anonymous", "greeting.list", "greeting", null,
                        AuditOutcome.DENIED, Map.of("orderBy", query.sortField())));
                throw new BizException(ErrorCodes.PARAM_INVALID, "不支持的排序字段");
            }
        };
        final Comparator<Greeting> ordered =
                "desc".equals(query.sortDirection()) ? comparator.reversed() : comparator;
        return all.stream().sorted(ordered).toList();
    }
}
