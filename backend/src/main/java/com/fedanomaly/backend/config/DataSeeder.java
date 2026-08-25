package com.fedanomaly.backend.config;

import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.model.NodeType;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class DataSeeder {

    @Bean
    public CommandLineRunner seedDatabase(NetworkNodeRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                List<NetworkNode> nodes = Arrays.asList(
                    createNode("inet-gw", "Internet Gateway", NodeType.ROUTER, 400, 50),
                    createNode("fw-1", "Main Firewall", NodeType.FIREWALL, 400, 150),
                    createNode("core-sw", "Core Switch", NodeType.ROUTER, 400, 250),
                    createNode("web-1", "Web Server", NodeType.SERVER, 200, 350),
                    createNode("app-1", "App Server", NodeType.SERVER, 400, 350),
                    createNode("db-1", "Database Server", NodeType.DATABASE, 600, 350),
                    createNode("vpn-gw", "VPN Gateway", NodeType.ROUTER, 800, 250),
                    createNode("admin-pc", "Admin PC", NodeType.PC, 800, 150),
                    createNode("dev-pc", "Developer PC", NodeType.PC, 150, 500),
                    createNode("emp-pc-1", "Employee PC 1", NodeType.PC, 250, 500),
                    createNode("emp-pc-2", "Employee PC 2", NodeType.PC, 350, 500),
                    createNode("iot-gw", "IoT Gateway", NodeType.ROUTER, 600, 500),
                    createNode("iot-1", "Smart Thermostat", NodeType.IOT_DEVICE, 500, 650),
                    createNode("iot-2", "Security Camera", NodeType.IOT_DEVICE, 600, 650),
                    createNode("iot-3", "Access Control", NodeType.IOT_DEVICE, 700, 650),
                    createNode("agg-srv", "Aggregation Server", NodeType.SERVER, 900, 350),
                    createNode("backup-srv", "Backup Server", NodeType.SERVER, 750, 450)
                );
                repository.saveAll(nodes);
                System.out.println("Seeded database with " + nodes.size() + " initial network nodes.");
            } else {
                // Ensure all nodes are healthy on restart
                List<NetworkNode> nodes = repository.findAll();
                for (NetworkNode node : nodes) {
                    node.setStatus(NodeStatus.HEALTHY);
                    node.setTrustScore(100.0);
                }
                repository.saveAll(nodes);
                System.out.println("Reset all nodes to HEALTHY state on startup.");
            }
        };
    }

    private NetworkNode createNode(String id, String name, NodeType type, double x, double y) {
        NetworkNode node = new NetworkNode();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        node.setTrustScore(100.0);
        node.setStatus(NodeStatus.HEALTHY);
        node.setPosX(x);
        node.setPosY(y);
        return node;
    }
}
