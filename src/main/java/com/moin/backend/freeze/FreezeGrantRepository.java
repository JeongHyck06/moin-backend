package com.moin.backend.freeze;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FreezeGrantRepository extends JpaRepository<FreezeGrant, String> {	void deleteByUserId(Long userId);
}
