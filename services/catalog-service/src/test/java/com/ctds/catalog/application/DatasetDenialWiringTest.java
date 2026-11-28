package com.ctds.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.DeclareLevel;
import com.ctds.catalog.domain.ProductReferenceGuard;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.TagTermPort;
import com.ctds.common.auth.AccessControl;
import com.ctds.common.errorcode.BizException;
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

@ExtendWith(MockitoExtension.class)
class DatasetDenialWiringTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T14:00:00Z"),
            ZoneOffset.UTC);

    @Mock
    private DatasetRepository repository;
    @Mock
    private CatalogAccessGuard guard;
    @Mock
    private AccessControl accessControl;
    @Mock
    private DatasetDenialService denialService;
    @Mock
    private DatasetRegistrationService registrationService;
    @Mock
    private ProductReferenceGuard productReferenceGuard;
    @Mock
    private TagTermPort tagTermPort;
    @Mock
    private CategoryPort categoryPort;
    @Mock
    private DataProductRepository productRepository;
    @Mock
    private SpaceMembershipPort spaceMembershipPort;
    @Mock
    private ProductCreationService productCreationService;

    private DatasetQueryService queryService;
    private DatasetCommandService commandService;
    private ProductCommandService productCommandService;

    @BeforeEach
    void setUp() {
        queryService = new DatasetQueryService(repository, guard, accessControl, denialService);
        commandService = new DatasetCommandService(repository, registrationService,
                productReferenceGuard, guard, tagTermPort, categoryPort, CLOCK, denialService);
        productCommandService = new ProductCommandService(productRepository, repository,
                spaceMembershipPort, categoryPort, productCreationService, guard, CLOCK,
                denialService);
        given(guard.requireSubject()).willReturn("subject");
    }

    @Test
    void detailRoutesMissingAndNonOwnerThroughSameDenialService() {
        given(repository.findById(1L)).willReturn(Optional.empty());
        given(repository.findById(2L)).willReturn(Optional.of(dataset(2L, "owner")));

        assertThatThrownBy(() -> queryService.detail(1L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> queryService.detail(2L)).isInstanceOf(BizException.class);

        verify(denialService).record("subject", "DENIED_READ", 1L);
        verify(denialService).record("subject", "DENIED_READ", 2L);
        verify(repository, never()).insertLog(any());
    }

    @Test
    void ownerDetailDoesNotEnterDenialWindow() {
        given(repository.findById(1L)).willReturn(Optional.of(dataset(1L, "subject")));

        assertThat(queryService.detail(1L).id()).isEqualTo(1L);
        verifyNoInteractions(denialService);
    }

    @Test
    void updateAndCancelRouteMissingThroughSameDenialService() {
        given(repository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.update(1L,
                new DatasetCommandService.DatasetUpdateRequest("intro", null, null, null)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> commandService.cancel(1L, true)).isInstanceOf(BizException.class);

        verify(denialService).record("subject", "DENIED_UPDATE", 1L);
        verify(denialService).record("subject", "DENIED_CANCEL", 1L);
        verify(repository, never()).insertLog(any());
    }

    @Test
    void productCreateRoutesMissingAndNonOwnerThroughSameDenialService() {
        given(repository.findById(1L)).willReturn(Optional.empty());
        given(repository.findById(2L)).willReturn(Optional.of(dataset(2L, "owner")));

        assertThatThrownBy(() -> productCommandService.create(product(1L)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> productCommandService.create(product(2L)))
                .isInstanceOf(BizException.class);

        verify(denialService).record("subject", "DENIED_CREATE", 1L);
        verify(denialService).record("subject", "DENIED_CREATE", 2L);
        verify(repository, never()).insertLog(any());
    }

    private static Dataset dataset(final long id, final String owner) {
        final LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new Dataset(id, "DS" + id, 1L, owner, "资源", "资源", DatasetType.DATASET,
                "简介", "[\"金融\"]", "金融", DeclareLevel.L2, false, DatasetStatus.ACTIVE,
                now, now);
    }

    private static ProductCommandService.CreateProductCommand product(final long datasetId) {
        return new ProductCommandService.CreateProductCommand(datasetId, "产品", "简介", "API",
                "FREE", null, null);
    }
}
