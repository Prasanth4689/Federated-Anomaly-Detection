package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.NetworkFlow;
public interface NetworkFlowRepository extends JpaRepository<NetworkFlow, Long> {}
