package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.FederatedRound;
import java.util.Optional;
public interface FederatedRoundRepository extends JpaRepository<FederatedRound, Long> {
    /** Latest recorded global model version (ties broken by insertion order). */
    Optional<FederatedRound> findTopByOrderByRoundNumberDescIdDesc();
}
