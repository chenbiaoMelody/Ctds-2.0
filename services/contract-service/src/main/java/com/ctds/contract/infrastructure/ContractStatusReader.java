package com.ctds.contract.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 关联合约状态批量只读（WBS-3.7.3 hifi §3.1/§3.4：订单读面 contractStatus 展示源；
 * 与订单表同库 ctds_contract——同库逻辑引用直读，零跨服务调用）。缺失返回 null
 * （异常数据兜底：不冒充状态——沿 1008S0003"不冒充"口径）。
 */
@Repository
public class ContractStatusReader {

    private final JdbcClient jdbc;

    public ContractStatusReader(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量读取合约状态（contract_no → status；缺失键不含于结果）。 */
    public Map<String, String> statusesOf(final Collection<String> contractNos) {
        if (contractNos.isEmpty()) {
            return Map.of();
        }
        final List<String> nos = List.copyOf(contractNos);
        final StringBuilder sql = new StringBuilder(
                "SELECT contract_no, status FROM contract WHERE contract_no IN (");
        for (int i = 0; i < nos.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        final Map<String, String> statuses = jdbc.sql(sql.toString())
                .params(nos)
                .query((rs, rowNum) -> Map.entry(rs.getString("contract_no"),
                        rs.getString("status")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first));
        return statuses;
    }

    /** 单合约状态（detail 面；缺失返回 null）。 */
    public String statusOf(final String contractNo) {
        return statusesOf(List.of(contractNo)).get(contractNo);
    }
}
