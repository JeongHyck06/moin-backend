package com.moin.backend.user;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PushDeviceRepository extends JpaRepository<PushDevice, String> {
	List<PushDevice> findByUserIdIn(Collection<Long> userIds);
	void deleteByTokenAndUserId(String token, Long userId);
}
