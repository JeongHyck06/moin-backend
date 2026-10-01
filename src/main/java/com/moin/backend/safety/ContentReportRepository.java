package com.moin.backend.safety;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ContentReportRepository extends JpaRepository<ContentReport,Long> {
 List<ContentReport> findByReporterIdAndKind(Long reporter,String kind);
 boolean existsByReporterIdAndKindAndTargetId(Long reporter,String kind,Long target);
 List<ContentReport> findByStatusOrderByCreatedAtAsc(String status,Pageable page);
 long countByReporterIdAndStatus(Long reporter,String status);
 void deleteByReporterIdOrTargetUserId(Long reporter,Long target);
}
