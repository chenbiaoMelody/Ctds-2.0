package com.ctds.example.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.example.application.GreetingService;
import com.ctds.example.domain.Greeting;
import com.ctds.example.interfaces.dto.GreetingRequest;
import com.ctds.example.interfaces.dto.GreetingView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口层：只依赖应用服务，禁止直连基础设施层（章程 4.3）。
 * traceId 由 common 的 TraceIdFilter 写入 MDC，封套自动携带。
 */
@RestController
@RequestMapping("/api/v1/greetings")
public class GreetingController {

    private final GreetingService greetingService;

    public GreetingController(final GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<GreetingView> create(@RequestBody final GreetingRequest request) {
        final Greeting saved = greetingService.greet(request.message());
        return ApiResult.ok(new GreetingView(saved.id(), saved.message()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<GreetingView>> list(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final String orderBy) {
        final PageResult<Greeting> page = greetingService.list(PageQuery.of(pageNum, pageSize, orderBy));
        final List<GreetingView> views = page.list().stream()
                .map(greeting -> new GreetingView(greeting.id(), greeting.message())).toList();
        return ApiResult.ok(new PageResult<>(views, page.total(), page.pageNum(), page.pageSize(), page.totalPages()));
    }
}
