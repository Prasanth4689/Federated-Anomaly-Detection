package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.NetworkNode;
public interface NetworkNodeRepository extends JpaRepository<NetworkNode, String> {}
