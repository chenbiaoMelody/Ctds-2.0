package com.ctds.catalog.application;

import com.ctds.catalog.domain.TagTerm;
import com.ctds.catalog.domain.TagTermPort;
import com.ctds.catalog.domain.TagVocabulary;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 受控词表读面服务（WBS-3.3.3 hifi §1.1 R3/R4）：词表册清单 + 词条分页。
 *
 * <p>边界（行为 7 规则 5）：响应恒仅词条元数据（编号/名称），无数据本体、无主体信息、无个人信息；
 * 认证与权限点 {@code vocabulary.read} 由接口层注解静态门把关（服务端强制——行为 7 规则 1）。
 * 词表为平台公开字典，读面<b>不做</b> ADMITTED 资格门槛（Q8-A）。</p>
 */
@Service
public class TagVocabularyQueryService {

    private final TagTermPort port;

    public TagVocabularyQueryService(final TagTermPort port) {
        this.port = port;
    }

    /** R3 词表册清单（本版恒 1 册；数据量级极小 → 全量返回）。 */
    public List<TagVocabulary> vocabularies() {
        return port.listVocabularies();
    }

    /**
     * R4 词条分页：册不存在 → 1007C0010（404，由仓储层册码解析时判定——单一失败语义，
     * 不做服务层预查）；与"册存在但 keyword 无命中 → 200 空列表"可分辨；
     * 稳定排序 = term_code 升序（分页跨页结果不漂移，ADR-005 §3.2）。
     */
    public PageResult<TagTerm> terms(final String vocabularyCode, final String keyword,
            final Integer pageNum, final Integer pageSize) {
        return port.pageTerms(vocabularyCode, keyword, PageQuery.of(pageNum, pageSize, null));
    }
}
