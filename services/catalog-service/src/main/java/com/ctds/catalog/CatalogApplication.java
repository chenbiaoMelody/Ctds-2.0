package com.ctds.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 目录与资源服务启动类（WBS 3.3.2，Q1-A 域级服务：3.3.2~3.3.5 共用宿主）。
 *
 * <p>本包承载资源登记模型与登记/变更/注销服务（规格 C-3.1 行为 1/2 + 行为 7 资源侧）；
 * 元数据采集与词表归 3.3.3、统一目录检索/订阅收藏归 3.3.4、产品封装与上下架归 3.3.5、
 * 界面归 3.3.6，按需在本服务内扩展（一域一服务，沿 space-service 先例）。</p>
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(final String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
