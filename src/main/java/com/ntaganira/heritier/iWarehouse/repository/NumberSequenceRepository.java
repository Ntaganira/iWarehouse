package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NumberSequenceRepository extends JpaRepository<NumberSequence, UUID> {

    /** SELECT ... FOR UPDATE: two documents of the same type never get the same number (MD-07). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NumberSequence s where s.docType = :type and s.branchCode = :branch")
    Optional<NumberSequence> findForUpdate(@Param("type") DocumentType type, @Param("branch") String branch);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NumberSequence s where s.id = :id")
    Optional<NumberSequence> lockById(@Param("id") UUID id);

    boolean existsByDocTypeAndBranchCode(DocumentType docType, String branchCode);

    boolean existsByPrefixAndBranchCode(String prefix, String branchCode);

    boolean existsByPrefixAndBranchCodeAndIdNot(String prefix, String branchCode, UUID id);

    boolean existsByBranchCode(String branchCode);
}
