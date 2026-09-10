package com.ctds.example.application;

import com.ctds.std.StdDomainApi;
import com.ctds.std.StdDomainStatus;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 应用服务：汇总标准能力域探活状态（WBS 2.4.8 H6）。
 * 输出顺序固定按域枚举声明序（互联互通 → DID 互认 → 测评证据），与容器 Bean 注入顺序解耦。
 */
@Service
public class StdCapabilityService {

    private final List<StdDomainApi> domainApis;

    public StdCapabilityService(final List<StdDomainApi> domainApis) {
        this.domainApis = List.copyOf(domainApis);
    }

    /** 三域状态清单（占位期全部 implemented=false）。 */
    public List<StdDomainStatus> statuses() {
        return domainApis.stream()
                .sorted(Comparator.comparingInt(api -> api.domain().ordinal()))
                .map(StdDomainApi::status)
                .toList();
    }
}
