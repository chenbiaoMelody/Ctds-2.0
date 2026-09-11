package com.ctds.example.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.example.application.DemoNoteService;
import com.ctds.example.interfaces.dto.DemoNoteView;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口层：迁移演示端点（WBS 2.4.10）。只依赖应用服务，禁止直连基础设施层（章程 4.3）。
 * 仅在 mysql profile（ctds.demo.db-enabled=true）装配——未启用时端点不存在（404），
 * 不产生"服务可用但数据不可用"的误导。
 */
@RestController
@RequestMapping("/api/v1/demo-notes")
@ConditionalOnProperty(name = "ctds.demo.db-enabled", havingValue = "true")
public class DemoNoteController {

    private final DemoNoteService demoNoteService;

    public DemoNoteController(final DemoNoteService demoNoteService) {
        this.demoNoteService = demoNoteService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<DemoNoteView>> list() {
        final List<DemoNoteView> views = demoNoteService.list().stream()
                .map(note -> new DemoNoteView(note.id(), note.title(), note.content(),
                        note.createdAt() == null ? null : note.createdAt().toString()))
                .toList();
        return ApiResult.ok(views);
    }
}
