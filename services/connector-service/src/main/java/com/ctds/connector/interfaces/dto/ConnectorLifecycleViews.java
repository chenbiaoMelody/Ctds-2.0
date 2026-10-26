package com.ctds.connector.interfaces.dto;

import com.ctds.connector.application.LifecycleSnapshot;
import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorJobOccupancy;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorVersionChange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生命周期（升级 / 版本兼容 / 注销）响应视图与请求模型（interfaces 层转换；不外露内部实现字段）。
 */
public final class ConnectorLifecycleViews {

    private ConnectorLifecycleViews() {
    }

    /** 升级登记请求（目标版本 + 兼容性声明）。 */
    public record UpgradeRequest(
            @NotBlank @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$") String toVersion,
            @NotBlank String compatibility) {
    }

    /** 在途作业占用登记请求（使用方提供的作业引用，不含秘密材料）。 */
    public record OccupancyRequest(@NotBlank @Size(min = 1, max = 64) String jobRef) {
    }

    /** 版本变更视图（升级单 + 版本历史链一节）。 */
    public record VersionChangeView(
            String upgradeNo,
            String connectorNo,
            String subjectNo,
            String fromVersion,
            String toVersion,
            String compatibility,
            String status,
            String requestedBy,
            String confirmedBy,
            LocalDateTime appliedAt,
            LocalDateTime createdAt) {

        public static VersionChangeView of(final ConnectorVersionChange change) {
            return new VersionChangeView(change.upgradeNo(), change.connectorNo(), change.subjectNo(),
                    change.fromVersion(), change.toVersion(),
                    change.compatibility() == null ? null : change.compatibility().name(),
                    change.status().name(), change.requestedBy(), change.confirmedBy(),
                    change.appliedAt(), change.createdAt());
        }
    }

    /** 占用视图（在途 / 已释放；记录保留可查）。 */
    public record OccupancyView(
            String connectorNo,
            String jobRef,
            String status,
            LocalDateTime occupiedAt,
            LocalDateTime releasedAt) {

        public static OccupancyView of(final ConnectorJobOccupancy occupancy) {
            return new OccupancyView(occupancy.connectorNo(), occupancy.jobRef(),
                    occupancy.status().name(), occupancy.occupiedAt(), occupancy.releasedAt());
        }
    }

    /** 生命周期聚合视图（注销后仍可读，承载可追溯义务）。 */
    public record LifecycleView(
            String connectorNo,
            String subjectNo,
            String connectorName,
            String connectorType,
            String connectorVersion,
            String status,
            boolean online,
            LocalDateTime lastHeartbeatAt,
            LocalDateTime cancelledAt,
            String bindingStatus,
            String did,
            LocalDateTime boundAt,
            LocalDateTime releasedAt,
            long inProgressJobs,
            boolean pendingUpgrade,
            List<VersionChangeView> versionHistory,
            LocalDateTime updatedAt) {

        public static LifecycleView of(final LifecycleSnapshot snapshot) {
            final ConnectorRegistration registration = snapshot.registration();
            final ConnectorBinding binding = snapshot.binding();
            return new LifecycleView(registration.connectorNo(), registration.subjectNo(),
                    registration.connectorName(), registration.connectorType(),
                    registration.connectorVersion(), registration.status().name(), snapshot.online(),
                    registration.lastHeartbeatAt(), registration.cancelledAt(),
                    binding == null ? null : binding.status().name(),
                    binding == null ? null : binding.did(),
                    binding == null ? null : binding.boundAt(),
                    binding == null ? null : binding.releasedAt(),
                    snapshot.inProgressJobs(), snapshot.pendingUpgrade(),
                    snapshot.versionHistory().stream().map(VersionChangeView::of).toList(),
                    registration.updatedAt());
        }
    }
}
