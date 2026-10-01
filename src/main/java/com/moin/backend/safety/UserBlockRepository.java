package com.moin.backend.safety;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserBlockRepository extends JpaRepository<UserBlock,Long> {
 List<UserBlock> findByOwnerIdOrTargetId(Long owner,Long target);
 List<UserBlock> findByOwnerId(Long owner);
 boolean existsByOwnerIdAndTargetId(Long owner,Long target);
 void deleteByOwnerIdAndTargetId(Long owner,Long target);
 void deleteByOwnerIdOrTargetId(Long owner,Long target);
}
