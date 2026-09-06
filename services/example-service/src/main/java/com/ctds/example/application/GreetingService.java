package com.ctds.example.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.example.domain.Greeting;
import com.ctds.example.domain.GreetingRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 应用服务：编排领域对象与仓储，禁止依赖接口层（章程 4.3）。
 * 内存实现的排序为演示用途；正式模块的排序在持久层执行（组件只负责参数契约）。
 */
@Service
public class GreetingService {

    private final GreetingRepository greetingRepository;

    public GreetingService(final GreetingRepository greetingRepository) {
        this.greetingRepository = greetingRepository;
    }

    public Greeting greet(final String message) {
        return greetingRepository.save(Greeting.of(message));
    }

    public PageResult<Greeting> list(final PageQuery query) {
        final List<Greeting> sorted = applySort(greetingRepository.findAll(), query);
        final int from = (int) Math.min(query.offset(), sorted.size());
        final int to = Math.min(from + query.pageSize(), sorted.size());
        return PageResult.of(sorted.subList(from, to), sorted.size(), query);
    }

    private List<Greeting> applySort(final List<Greeting> all, final PageQuery query) {
        if (query.sortField() == null) {
            return all;
        }
        final Comparator<Greeting> comparator = switch (query.sortField()) {
            case "message" -> Comparator.comparing(Greeting::message);
            case "id" -> Comparator.comparing(Greeting::id);
            default -> throw new BizException(ErrorCodes.PARAM_INVALID, "不支持的排序字段");
        };
        final Comparator<Greeting> ordered =
                "desc".equals(query.sortDirection()) ? comparator.reversed() : comparator;
        return all.stream().sorted(ordered).toList();
    }
}
