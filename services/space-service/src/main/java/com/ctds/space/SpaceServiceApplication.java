package com.ctds.space;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 空间服务启动类（WBS 3.2.2）。
 *
 * <p>本包只承载数据模型（建表迁移 + 领域模型类）：无接口层、无应用服务、无 Repository——
 * 生命周期服务归 3.2.3、成员与权限归 3.2.4、策略继承引擎归 3.2.5，按需在对应包内扩展。</p>
 */
@SpringBootApplication
public class SpaceServiceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(SpaceServiceApplication.class, args);
    }
}
