package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.FederatedRound;
public interface FederatedRoundRepository extends JpaRepository<FederatedRound, Long> {}
