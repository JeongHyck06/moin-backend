package com.moin.backend.storage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingFileDeletionRepository extends JpaRepository<PendingFileDeletion, String> {}
