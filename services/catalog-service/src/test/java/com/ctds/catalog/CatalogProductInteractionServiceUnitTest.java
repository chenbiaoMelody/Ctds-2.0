package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.ctds.catalog.application.CatalogAccessGuard;
import com.ctds.catalog.application.ProductInteractionService;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProductSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * DB-34 收口单测（WBS-3.3.5 评审循环 1 T2：并发唯一键命中 → catch DuplicateKeyException →
 * 重放首次结果分支的确定性触发——集成测试预插行走"先查命中提前返回"分支，catch 体零触达；
 * 本单测经 mock 使先查为空、插入抛唯一键冲突、再查返回既有值，精确覆盖 catch 重放路径）。
 * favorite 与 subscribe 双路径各 1 例（DB-34 修复点两处）。
 */
@ExtendWith(MockitoExtension.class)
class CatalogProductInteractionServiceUnitTest {

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(1_760_000_000L), ZoneOffset.UTC);

    @Mock
    private DataProductRepository dataProductRepository;

    @Mock
    private com.ctds.catalog.domain.ProductFavoriteRepository favoriteRepository;

    @Mock
    private ProductSubscriptionRepository subscriptionRepository;

    @Mock
    private com.ctds.catalog.domain.ProductInteractionLogRepository interactionLogRepository;

    @Mock
    private CatalogAccessGuard guard;

    private ProductInteractionService service;

    @BeforeEach
    void setUp() {
        service = new ProductInteractionService(dataProductRepository, favoriteRepository,
                subscriptionRepository, interactionLogRepository, guard,
                Clock.fixed(Instant.ofEpochSecond(1_760_000_000L), ZoneOffset.UTC));
    }

    private void stubCommon() {
        given(guard.requireSubject()).willReturn("reader-d34");
        given(dataProductRepository.findStatusById(anyLong())).willReturn(Optional.of(
                ProductStatus.LISTED));
    }

    @Test
    void favoriteDuplicateKeyReplaysFirstResult() {
        stubCommon();
        given(favoriteRepository.findFavoritedAt(anyString(), anyLong()))
                .willReturn(Optional.empty(), Optional.of(NOW));
        willThrow(new DuplicateKeyException("uk_subject_product")).given(favoriteRepository)
                .insertWithLog(anyString(), anyLong(), any(), any());

        final LocalDateTime replayed = service.favorite(7L);

        assertThat(replayed).as("DB-34：并发唯一键命中 → catch 重放首次结果（非 500）")
                .isEqualTo(NOW);
    }

    @Test
    void subscribeDuplicateKeyReplaysFirstResult() {
        stubCommon();
        given(subscriptionRepository.findSubscribedAt(anyString(), anyLong()))
                .willReturn(Optional.empty(), Optional.of(NOW));
        willThrow(new DuplicateKeyException("uk_subject_product")).given(subscriptionRepository)
                .insertWithLog(anyString(), anyLong(), any(), any());

        final LocalDateTime replayed = service.subscribe(7L);

        assertThat(replayed).as("DB-34 订阅侧：catch 重放首次结果").isEqualTo(NOW);
    }
}
