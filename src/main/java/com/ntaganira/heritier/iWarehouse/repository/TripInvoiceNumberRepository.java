package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripInvoiceNumber;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripInvoiceNumberRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Invoice numbers handed to a trip's phones (SYNC-01).
 * </pre>
 */
public interface TripInvoiceNumberRepository extends JpaRepository<TripInvoiceNumber, UUID> {

    /** A phone's numbers for a trip, in order. */
    List<TripInvoiceNumber> findByTripIdAndDeviceIdOrderByNumber(UUID tripId, UUID deviceId);

    List<TripInvoiceNumber> findByTripIdOrderByNumber(UUID tripId);

    /** A number, locked: a sale uses it once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from TripInvoiceNumber n where n.number = :number")
    Optional<TripInvoiceNumber> lockByNumber(@Param("number") String number);
}
