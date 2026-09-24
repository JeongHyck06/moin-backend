package com.moin.backend.storage;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DB 삭제가 커밋된 뒤 지울 파일 경로, 서버 재시작 뒤에도 재시도 */
@Entity
@Getter
@NoArgsConstructor
public class PendingFileDeletion {
    @Id private String path;
    public PendingFileDeletion(String path) { this.path = path; }
}
