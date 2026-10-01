package com.moin.backend.safety;
import jakarta.persistence.*;
import lombok.*;
@Entity @Getter @NoArgsConstructor
@Table(name="user_blocks", uniqueConstraints=@UniqueConstraint(columnNames={"ownerId","targetId"}))
public class UserBlock {
 @Id @GeneratedValue private Long id;
 @Column(nullable=false) private Long ownerId;
 @Column(nullable=false) private Long targetId;
 public UserBlock(Long ownerId, Long targetId) { this.ownerId=ownerId; this.targetId=targetId; }
}
