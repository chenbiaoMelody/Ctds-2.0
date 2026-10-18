package com.ctds.delivery.application;

import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryActionLog;
import com.ctds.delivery.domain.DeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 交付记录 + 动作留痕同事务落库（外部调用/加密在事务外完成）。 */
@Service
public class DeliveryPersistenceService {

    private final DeliveryRepository repository;

    public DeliveryPersistenceService(final DeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void persist(final Delivery delivery, final DeliveryActionLog actionLog) {
        repository.insert(delivery);
        repository.insertActionLog(actionLog);
    }
}
