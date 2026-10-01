package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;

/**
 * 受控词表读端口 + 成员校验端口（WBS-3.3.3 hifi §8；实现 = infrastructure.JdbcTagTermRepository，
 * 沿 {@link SubjectAdmissionPort} / {@link SpaceMembershipPort} 端口-适配器先例）。
 *
 * <p>词表为<b>同库本地字典</b>：无网络调用、无 UNAVAILABLE 分支，故本卡不新增 S 型错误码。
 * 读面与写面共用同一端口——成员校验与选词入口同源，口径不可能漂移。</p>
 */
public interface TagTermPort {

    /** 词表册清单（本版恒 1 册，数据量级极小 → 全量返回，hifi §1.1 R3）。 */
    List<TagVocabulary> listVocabularies();

    /** 词条分页（按所属册；keyword 为空 = 不过滤；稳定排序 = term_code 升序，hifi §1.1 R4）。 */
    PageResult<TagTerm> pageTerms(String vocabularyCode, String keyword, PageQuery page);

    /**
     * 成员校验差集（hifi §4.3 匹配口径唯一实现）：入参标签经 {@link DatasetNameNormalizer}
     * 归一化去重后，与指定册的归一化词条名做差集。
     *
     * @param tags 原始标签（可为 null / 空）
     * @return 未匹配词表的归一化标签名列表（空 = 全部命中即通过）；**不用于对外文案**（禁止回显）
     */
    List<String> findUnmatched(String vocabularyCode, List<String> tags);
}
