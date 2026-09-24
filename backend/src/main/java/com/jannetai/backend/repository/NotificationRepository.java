package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Notification;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 15
 * (Notification Module, SRS 15.13 / 20.5 {@code GET /api/v1/notifications})
 * with the one query the recipient-facing list screen needs: a user's own
 * notifications, most recent first. Deliberately scoped to
 * {@code user_id} only (not complaint/channel filters) - the SRS's API
 * table gives no query parameters for this endpoint beyond pagination.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUser_UserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Phase 16 (Admin Dashboard, SRS 24.3 "system health indicators") -
     * see {@code AdminDashboardSummaryResponse}'s Javadoc for why
     * notification-delivery health stands in for the SRS's separate,
     * out-of-scope infrastructure-monitoring section. {@code FAILED}
     * here means a delivery that exhausted Phase 15's
     * {@code app.notification.max-delivery-attempts} retries - not a
     * transient {@code PENDING} row still mid-retry.
     */
    long countByDeliveryStatusAndCreatedAtAfter(DeliveryStatus deliveryStatus, LocalDateTime since);

    long countByCreatedAtAfter(LocalDateTime since);
}
