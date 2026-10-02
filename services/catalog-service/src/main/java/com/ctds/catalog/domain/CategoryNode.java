package com.ctds.catalog.domain;

/**
 * 受控类目节点（WBS-3.3.4 hifi §3：category_node 行 = 类目树一个节点，2 级树）。
 *
 * <p>平台唯一受控类目集合（Q2-A）：与语义标签词表为<b>两套受控集合</b>（3.3.3 hifi §9 红线），
 * 术语面（category/categories）与词表（vocabulary/term）物理分离。不含数据本体、主体信息与
 * 个人信息（行为 7 规则 5）；V1.0 平台受控、随迁移内置、无维护写面（只读）。</p>
 *
 * @param id                    技术主键
 * @param categoryCode          类目码（语义码小写连字符，平台受控）
 * @param categoryName          类目名（展示用；资源分类申报落库存原文）
 * @param normalizedCategoryName 归一化类目名（应用侧 DatasetNameNormalizer 产出，成员校验比对口径）
 * @param parentCode            父类目码（null = 一级类目）
 * @param sortOrder             同级展示排序（升序）
 */
public record CategoryNode(long id, String categoryCode, String categoryName, String normalizedCategoryName,
        String parentCode, int sortOrder) {

    public CategoryNode {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new IllegalArgumentException("类目码不能为空");
        }
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("类目名不能为空");
        }
        if (normalizedCategoryName == null || normalizedCategoryName.isBlank()) {
            throw new IllegalArgumentException("归一化类目名不能为空");
        }
    }

    /** 是否一级类目（parent_code 为空）。 */
    public boolean isTopLevel() {
        return parentCode == null;
    }
}
