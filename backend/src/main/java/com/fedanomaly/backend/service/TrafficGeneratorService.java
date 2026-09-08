package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.Packet;
import com.fedanomaly.backend.repository.NetworkNodeRepository;

import java.util.List;
import java.util.Random;

@Service
public class TrafficGeneratorService {
    private final NetworkNodeRepository nodeRepository;
    private final FlowService flowService;
    private final AttackService attackService;
    private final Random random = new Random();

    // Realistic port distributions per node type
    private static final int[] WEB_PORTS = {80, 443, 8080, 8443};
    private static final int[] DB_PORTS = {3306, 5432, 27017, 6379};
    private static final int[] IOT_PORTS = {1883, 8883, 5683}; // MQTT, CoAP
    private static final String[] PROTOCOLS = {"TCP", "UDP", "HTTP", "HTTPS", "DNS"};

    // External-profile node IDs that can act as attack sources
    private static final String[] EXTERNAL_SOURCES = {"inet-gw", "vpn-gw"};
    // Internal PCs that could be compromised
    private static final String[] INTERNAL_PCS = {"dev-pc", "emp-pc-1", "emp-pc-2", "admin-pc"};

    public TrafficGeneratorService(NetworkNodeRepository nodeRepository, FlowService flowService, AttackService attackService) {
        this.nodeRepository = nodeRepository;
        this.flowService = flowService;
        this.attackService = attackService;
    }

    public void generateTraffic() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        if (nodes.size() < 2) return;

        // Generate normal traffic based on node profiles
        int numPackets = random.nextInt(10) + 5;
        for (int i = 0; i < numPackets; i++) {
            NetworkNode src = nodes.get(random.nextInt(nodes.size()));
            NetworkNode dst = nodes.get(random.nextInt(nodes.size()));
            if (src.getId().equals(dst.getId())) continue;

            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(dst.getId());
            p.setProtocol(getProtocolForType(src.getType().name()));
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(getDestPortForType(dst.getType().name()));
            p.setBytes(getTypicalBytesForType(src.getType().name()));
            p.setLabel("NORMAL");

            flowService.receivePacket(p);
        }

        // Generate attack traffic — ALL packets use label "NORMAL" so ML must detect from features
        attackService.getActiveAttacks().forEach((target, type) -> {
            String normalised = type.toUpperCase();
            if (normalised.startsWith("DDOS")) {
                generateDDoS(nodes, target);
            } else if (normalised.startsWith("PORT")) {
                generatePortScan(nodes, target);
            } else if (normalised.startsWith("BRUTE")) {
                generateBruteForce(nodes, target);
            } else if (normalised.startsWith("MALWARE")) {
                generateMalwareBeaconing(nodes, target);
            } else if (normalised.startsWith("DATA") || normalised.startsWith("EXFIL")) {
                generateDataExfiltration(nodes, target);
            } else if (normalised.startsWith("RECON")) {
                generateReconnaissance(nodes, target);
            }
        });
    }

    // --- Attack Traffic Generators ---
    // Key change: label is always "NORMAL" — detection comes from ML observing
    // anomalous traffic FEATURES (volume, packet size, port diversity, rate)

    private void generateDDoS(List<NetworkNode> nodes, String target) {
        // DDoS: high-volume small packets from multiple external sources
        // Feature signature: very high packets_per_sec, very low avg_packet_size, low port_diversity
        for (int i = 0; i < 50; i++) {
            Packet p = new Packet();
            // Rotate among external sources to simulate botnet
            p.setSource(EXTERNAL_SOURCES[random.nextInt(EXTERNAL_SOURCES.length)]);
            p.setDest(target);
            p.setProtocol(random.nextBoolean() ? "TCP" : "UDP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(80); // All targeting same port — low diversity
            p.setBytes(32 + random.nextInt(32)); // Very small packets (32-64 bytes)
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    private void generatePortScan(List<NetworkNode> nodes, String target) {
        // Port scanning: sequential port probing from a compromised internal PC
        // Feature signature: very high port_diversity, small packets, many flows
        String srcId = INTERNAL_PCS[random.nextInt(INTERNAL_PCS.length)];
        NetworkNode src = nodes.stream().filter(n -> n.getId().equals(srcId)).findFirst().orElse(nodes.get(0));
        int startPort = random.nextInt(1000);
        for (int port = startPort; port < startPort + 100; port++) {
            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(target);
            p.setProtocol("TCP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(port); // Sequential unique ports — high diversity
            p.setBytes(40 + random.nextInt(24)); // SYN packets are very small
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    private void generateBruteForce(List<NetworkNode> nodes, String target) {
        // Brute force: repeated login attempts from external, targeting auth ports
        // Feature signature: many packets to same port (low diversity), medium size, high rate
        String srcId = EXTERNAL_SOURCES[random.nextInt(EXTERNAL_SOURCES.length)];
        int destPort = random.nextBoolean() ? 22 : 3389; // SSH or RDP
        for (int i = 0; i < 30; i++) {
            Packet p = new Packet();
            p.setSource(srcId);
            p.setDest(target);
            p.setProtocol("TCP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(destPort);
            p.setBytes(128 + random.nextInt(256)); // Login payload
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    private void generateMalwareBeaconing(List<NetworkNode> nodes, String target) {
        // Malware: compromised node phones home to C2 with periodic small callbacks
        // Feature signature: outbound traffic, small consistent-sized packets, same dest port
        for (int i = 0; i < 5; i++) {
            Packet p = new Packet();
            p.setSource(target); // Compromised node phones home
            p.setDest("inet-gw"); // Beaconing to external
            p.setProtocol("HTTPS");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(443);
            p.setBytes(64 + random.nextInt(128)); // Small beacon
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    private void generateDataExfiltration(List<NetworkNode> nodes, String target) {
        // Exfiltration: large outbound data transfers from compromised node
        // Feature signature: very high bytes_per_sec, large avg_packet_size
        for (int i = 0; i < 10; i++) {
            Packet p = new Packet();
            p.setSource(target); // Exfiltrating FROM this node
            p.setDest("inet-gw"); // Sending to external
            p.setProtocol("HTTPS");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(443);
            p.setBytes(8000 + random.nextInt(7000)); // Large data chunks
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    private void generateReconnaissance(List<NetworkNode> nodes, String target) {
        // Recon: probing from target to structural servers
        // Feature signature: ICMP probes to multiple targets, small packets
        NetworkNode src = nodes.stream().filter(n -> n.getId().equals(target)).findFirst().orElse(nodes.get(0));
            
        List<NetworkNode> victims = nodes.stream()
            .filter(n -> n.getId().contains("srv") || n.getId().contains("db") || n.getId().contains("fw"))
            .limit(5)
            .collect(java.util.stream.Collectors.toList());
            
        for (NetworkNode dst : victims) {
            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(dst.getId());
            p.setProtocol("ICMP");
            p.setSourcePort(0);
            p.setDestPort(random.nextBoolean() ? 80 : 443);
            p.setBytes(64);
            p.setLabel("ATTACK");
            flowService.receivePacket(p);
        }
    }

    // --- Node Profile Helpers ---

    private String getProtocolForType(String type) {
        return switch (type) {
            case "SERVER" -> PROTOCOLS[random.nextInt(3)]; // TCP, UDP, HTTP
            case "DATABASE" -> "TCP";
            case "IOT_DEVICE" -> random.nextBoolean() ? "UDP" : "TCP";
            case "FIREWALL", "ROUTER" -> PROTOCOLS[random.nextInt(PROTOCOLS.length)];
            default -> "TCP"; // PC, LAPTOP
        };
    }

    private int getDestPortForType(String type) {
        return switch (type) {
            case "SERVER" -> WEB_PORTS[random.nextInt(WEB_PORTS.length)];
            case "DATABASE" -> DB_PORTS[random.nextInt(DB_PORTS.length)];
            case "IOT_DEVICE" -> IOT_PORTS[random.nextInt(IOT_PORTS.length)];
            default -> 80 + random.nextInt(400);
        };
    }

    private int getTypicalBytesForType(String type) {
        return switch (type) {
            case "IOT_DEVICE" -> 64 + random.nextInt(256);   // small IoT payloads
            case "SERVER" -> 256 + random.nextInt(1200);      // medium web responses
            case "DATABASE" -> 512 + random.nextInt(2000);    // query results
            default -> 128 + random.nextInt(1000);            // general traffic
        };
    }
}
