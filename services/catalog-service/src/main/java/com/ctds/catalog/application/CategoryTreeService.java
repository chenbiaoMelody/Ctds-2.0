package com.ctds.catalog.application;

import com.ctds.catalog.domain.CategoryNode;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.interfaces.dto.CategoryNodeView;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 类目树服务（WBS-3.3.4 hifi §8；R5 类目树读面组装 + R6 分类过滤子树展开的领域侧支撑）。
 * 类目树 = 平台唯一受控类目集合（Q2-A）：V1.0 平台受控、随迁移内置、无维护写面（只读），
 * 与语义标签词表两套受控集合物理分离（3.3.3 hifi §9 红线——本服务不触碰词表通道）。
 */
@Service
public class CategoryTreeService {

    private final CategoryPort categoryPort;

    public CategoryTreeService(final CategoryPort categoryPort) {
        this.categoryPort = categoryPort;
    }

    /**
     * 全量 2 级树（R5，hifi §1）：同级按 sort_order 升序；一级 children = 其二级，二级 children 恒空。
     * 种子 24 条量级极小 → 全量返回后内存组树（无分页）。
     */
    public List<CategoryNodeView> tree() {
        final List<CategoryNode> all = categoryPort.listAll();
        final Comparator<CategoryNode> bySortOrder = Comparator.comparingInt(CategoryNode::sortOrder);
        return all.stream()
                .filter(CategoryNode::isTopLevel)
                .sorted(bySortOrder)
                .map(top -> new CategoryNodeView(top.categoryCode(), top.categoryName(),
                        childrenOf(all, top.categoryCode(), bySortOrder)))
                .toList();
    }

    private static List<CategoryNodeView> childrenOf(final List<CategoryNode> all, final String parentCode,
            final Comparator<CategoryNode> bySortOrder) {
        return all.stream()
                .filter(node -> parentCode.equals(node.parentCode()))
                .sorted(bySortOrder)
                .map(CategoryNodeView::leaf)
                .toList();
    }
}
