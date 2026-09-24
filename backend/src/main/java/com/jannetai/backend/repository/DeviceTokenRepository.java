package com.jannetai.backend.repository;

import com.jannetai.backend.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUser_UserIdAndIsActiveTrue(Long userId);

    Optional<DeviceToken> findByDeviceToken(String deviceToken);

    void deleteByDeviceToken(String deviceToken);
}
