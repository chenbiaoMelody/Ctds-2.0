package com.ctds.catalog.domain;

import java.util.List;

/**
 * 受控类目树读端口 + 类目成员校验端口（WBS-3.3.4 hifi §3/§4；实现 = infrastructure.JdbcCategoryRepository，
 * 沿 {@link TagTermPort} / {@link SubjectAdmissionPort} 端口-适配器先例）。
 *
 * <p>类目树为<b>同库本地字典</b>：无网络调用、无 UNAVAILABLE 分支，故本卡不新增 S 型错误码。
 * 类目树与语义标签词表为两套受控集合（3.3.3 hifi §9 红线）——本端口与 {@link TagTermPort}
 * 物理分离，通道互不混用；读面（R5/R6）与写面成员校验（W1/W2 插入）共用同一端口，
 * 目录过滤与申报准入口径同源，不可能漂移。</p>
 */
public interface CategoryPort {

    /** 全量类目清单（种子 24 条，量级极小 → 全量返回，R5 树组装输入；按 id 升序）。 */
    List<CategoryNode> listAll();

    /** 类目码存在性（R6 参数校验：categoryCode 须为类目树内节点，否则 1007C0013）。 */
    boolean existsByCode(String categoryCode);

    /** 类目码自身与后代码集（2 级树：一次查询取该节点及直接子级；R6 分类过滤子树展开）。 */
    List<String> selfAndDescendantCodes(String categoryCode);

    /**
     * 类目成员校验（hifi §3 匹配口径唯一实现）：申报原文经 {@link DatasetNameNormalizer} 归一化后，
     * 与 {@code category_node.normalized_name} 在 <b>DB 侧一次比对</b>（沿 3.3.3 教训不搞
     * "DB 判定 + Java 原文差集"两道口径；大小写折叠由列排序规则 0900_ai_ci 承担）。归一化单点在
     * 本端口实现内（沿 {@link TagTermPort#findUnmatched} 词表通道同款），调用方传申报原文、
     * 不做第二次归一化。
     *
     * @param declareCategory 资源分类申报原文（落库同值，不改写）
     * @return true = 在受控类目集合内（通过）
     */
    boolean existsByNormalizedName(String declareCategory);

    /**
     * 按申报原文定位类目码（WBS-3.3.5 hifi §4 W8 步骤 ⑥：产品类目缺省继承——资源
     * {@code declare_category} 经归一化在 DB 侧一次比对定位 {@code category_code}；匹配不到
     * （申报早于类目树/值不在树）返回 null，由调用方落产品类目 NULL、不阻断封装（Q2-A 传导）。
     * 归一化单点同 {@link #existsByNormalizedName}。
     */
    String findCodeByNormalizedName(String declareCategory);
}
