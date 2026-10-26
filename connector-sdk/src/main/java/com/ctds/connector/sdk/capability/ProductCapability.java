package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.PageResult;
import com.ctds.connector.sdk.model.ProductView;

/**
 * 产品管理门面（规格行为 2 产品腿）：产品上架 / 下架 / 四形态由目录域提供——SDK 只封装。
 */
public interface ProductCapability {

    /** 发布产品（平台产品管理面返回，形态 / 定价档 / 状态为业务可读中文）。 */
    ProductView publishProduct(CreateProductCommand command);

    /** 查我的产品（平台分页信封；pageNum ≥1，pageSize 1~100）。 */
    PageResult<ProductView> listMyProducts(int pageNum, int pageSize);
}
