package com.moin.backend.safety;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(name="content_reports", uniqueConstraints=@UniqueConstraint(columnNames={"reporterId","kind","targetId"}))
public class ContentReport {
 @Id @GeneratedValue private Long id;
 @Column(nullable=false) private Long reporterId;
 @Column(nullable=false) private Long targetUserId;
 @Column(nullable=false) private Long groupId;
 @Column(nullable=false) private Long targetId;
 @Column(nullable=false) private String kind;
 @Column(nullable=false,length=500) private String reason;
 @Column(nullable=false) private String status="OPEN";
 @Column(nullable=false) private Instant createdAt;
 private Instant resolvedAt;
 private Long reviewerId;
}
