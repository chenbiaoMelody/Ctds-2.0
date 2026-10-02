package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CategoryNode;
import java.util.Comparator;
import java.util.List;

/**
 * 类目树节点视图（WBS-3.3.4 hifi §1 R5 出参：categoryCode / categoryName / children[] 同构；
 * 2 级树——一级节点 children = 二级节点，二级 children 恒空）。仅目录元数据（行为 7 规则 5）。
 */
public record CategoryNodeView(String categoryCode, String categoryName, List<CategoryNodeView> children) {

    public CategoryNodeView {
        children = (children == null) ? List.of() : List.copyOf(children);
    }

    /**
     * 自全量平铺清单组装 2 级树出参（R5，hifi §1）：一级同级按 sort_order 升序；
     * 一级 children = 其二级（同级升序），二级 children 恒空。
     */
    public static List<CategoryNodeView> treeOf(final List<CategoryNode> nodes) {
        final Comparator<CategoryNode> bySortOrder = Comparator.comparingInt(CategoryNode::sortOrder);
        return nodes.stream()
                .filter(CategoryNode::isTopLevel)
                .sorted(bySortOrder)
                .map(top -> new CategoryNodeView(top.categoryCode(), top.categoryName(),
                        childrenOf(nodes, top.categoryCode(), bySortOrder)))
                .toList();
    }

    private static List<CategoryNodeView> childrenOf(final List<CategoryNode> nodes, final String parentCode,
            final Comparator<CategoryNode> bySortOrder) {
        return nodes.stream()
                .filter(node -> parentCode.equals(node.parentCode()))
                .sorted(bySortOrder)
                .map(CategoryNodeView::leaf)
                .toList();
    }

    /** 组装叶子（二级）节点。 */
    public static CategoryNodeView leaf(final CategoryNode node) {
        return new CategoryNodeView(node.categoryCode(), node.categoryName(), List.of());
    }
}
