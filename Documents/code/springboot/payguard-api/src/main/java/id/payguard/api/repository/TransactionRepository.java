package id.payguard.api.repository;

import id.payguard.api.entity.Transaction;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByMerchantIdAndOrderId(String merchantId, String orderId);

    /**
     Meminta database mengunci baris transaksi yang ditemukan agar
     transaksi lain tidak bisa mengubah atau mengunci baris tersebut untuk
     penulisan secara bersamaan.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdPessimisticLock(@Param("id") UUID id);
}
