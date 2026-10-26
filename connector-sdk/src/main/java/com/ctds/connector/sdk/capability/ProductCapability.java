package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.ProductView;
import java.util.List;

/**
 * 产品管理门面（规格行为 2 产品腿）：产品上架 / 下架 / 四形态由目录域提供——SDK 只封装。
 */
public interface ProductCapability {

    /** 发布产品。 */
    ProductView publishProduct(CreateProductCommand command);

    /** 查我的产品。 */
    List<ProductView> listMyProducts();
}
