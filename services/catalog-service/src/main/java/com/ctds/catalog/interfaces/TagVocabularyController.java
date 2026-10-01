package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.TagVocabularyQueryService;
import com.ctds.catalog.domain.TagTerm;
import com.ctds.catalog.interfaces.dto.TagTermView;
import com.ctds.catalog.interfaces.dto.TagVocabularyView;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控词表读端点（WBS-3.3.3 hifi §1.1 R3/R4；ADR-005 资源命名 /api/v1/tag-vocabularies）。
 *
 * <p>权限点 {@code vocabulary.read} 为功能第一道门槛（服务端强制——行为 7 规则 1）；本卡
 * <b>不做</b>词表维护写面（Q7-A），故无写端点。响应恒仅词条元数据（行为 7 规则 5）。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class TagVocabularyController {

    private final TagVocabularyQueryService queryService;

    public TagVocabularyController(final TagVocabularyQueryService queryService) {
        this.queryService = queryService;
    }

    /** R3 词表册清单（全量——本版仅 1 册）。 */
    @GetMapping(path = "/tag-vocabularies", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("vocabulary.read")
    public ApiResult<List<TagVocabularyView>> vocabularies() {
        final List<TagVocabularyView> views = queryService.vocabularies().stream()
                .map(TagVocabularyView::from)
                .toList();
        return ApiResult.ok(views);
    }

    /** R4 词条分页（keyword 名称模糊可选；term_code 升序稳定排序；册不存在 → 1007C0010 / 404）。 */
    @GetMapping(path = "/tag-vocabularies/{vocabularyCode}/terms",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("vocabulary.read")
    public ApiResult<PageResult<TagTermView>> terms(@PathVariable final String vocabularyCode,
            @RequestParam(required = false) final String keyword,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<TagTerm> result = queryService.terms(vocabularyCode, keyword, pageNum, pageSize);
        final PageResult<TagTermView> views = new PageResult<>(
                result.list().stream().map(TagTermView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
        return ApiResult.ok(views);
    }
}
