package com.ctds.contract;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 合约服务启动类（WBS-3.4.2，Q1-A 域级服务：3.4.2~3.4.8 共用宿主）。
 *
 * <p>本包当前承载合约模板库服务（规格 C-4.1~4.3 行为 1，模板条款槽位集合与模板数据模型为
 * 规格授权本卡落定的未定义项）；合约协商与电子签署归 3.4.3、策略 DSL/执行引擎归 3.4.4/3.4.5、
 * 模拟器与测试台归 3.4.6、合约工作台界面归 3.4.7，按需在本服务内扩展（一域一服务，
 * 沿 catalog-service 先例）。</p>
 */
@SpringBootApplication
public class ContractApplication {

    public static void main(final String[] args) {
        SpringApplication.run(ContractApplication.class, args);
    }
}
