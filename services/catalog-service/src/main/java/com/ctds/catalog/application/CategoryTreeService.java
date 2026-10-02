package com.ctds.catalog.application;

import com.ctds.catalog.domain.CategoryNode;
import com.ctds.catalog.domain.CategoryPort;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 类目树服务（WBS-3.3.4 hifi §8；R5 类目树读面的领域侧支撑）。
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
     * 全量类目平铺清单（R5 组树输入，hifi §1）：2 级树全量返回（种子 24 条量级极小，无分页），
     * 层级关系见 {@code parentCode}。树形出参（同级 sort_order 升序 + children 嵌套）属响应表示，
     * 由接口层 {@code CategoryNodeView.treeOf} 组装——应用服务返领域对象、不返接口层 DTO（分层惯例）。
     */
    public List<CategoryNode> allNodes() {
        return categoryPort.listAll();
    }
}
