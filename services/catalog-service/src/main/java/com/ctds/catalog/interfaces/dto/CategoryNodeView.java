package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CategoryNode;
import java.util.List;

/**
 * 类目树节点视图（WBS-3.3.4 hifi §1 R5 出参：categoryCode / categoryName / children[] 同构；
 * 2 级树——一级节点 children = 二级节点，二级 children 恒空）。仅目录元数据（行为 7 规则 5）。
 */
public record CategoryNodeView(String categoryCode, String categoryName, List<CategoryNodeView> children) {

    public CategoryNodeView {
        children = (children == null) ? List.of() : List.copyOf(children);
    }

    /** 组装叶子（二级）节点。 */
    public static CategoryNodeView leaf(final CategoryNode node) {
        return new CategoryNodeView(node.categoryCode(), node.categoryName(), List.of());
    }
}
