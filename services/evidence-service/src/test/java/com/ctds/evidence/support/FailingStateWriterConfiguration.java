package com.ctds.evidence.support;

import com.ctds.evidence.application.EvidenceStateWriter;
import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可控状态写入失败（P2-3 修复批用例的唯一故障构造手段）：置 {@link #FAIL_NEXT} 后
 * 下一次状态 / 留痕写入抛数据库异常，消费一次即复位。生产代码无运行期故障开关（Q7-A）。
 */
@TestConfiguration
public class FailingStateWriterConfiguration {

    public static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);

    @Bean
    @Primary
    public EvidenceStateWriter failingEvidenceStateWriter(final EvidenceRepository repository) {
        return new EvidenceStateWriter(repository) {
            @Override
            @Transactional
            public void transition(final EvidenceRecord updated, final EvidenceActionLog action) {
                if (FAIL_NEXT.compareAndSet(true, false)) {
                    throw new DataAccessResourceFailureException("测试构造：状态写入失败");
                }
                super.transition(updated, action);
            }
        };
    }
}
