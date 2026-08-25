package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.SimulationEvent;
public interface SimulationEventRepository extends JpaRepository<SimulationEvent, Long> {}
