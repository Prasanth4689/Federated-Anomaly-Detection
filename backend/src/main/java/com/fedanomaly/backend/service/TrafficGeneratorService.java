package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.model.Packet;
import com.fedanomaly.backend.repository.NetworkNodeRepository;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TrafficGeneratorService {
    private final NetworkNodeRepository nodeRepository;
    private final FlowService flowService;
    private final AttackService attackService;
    private final Random random = new Random();

    // Simulated clock: each tick is one observation window of WINDOW_MS
    private static final int WINDOW_MS = 500;
    private long simClockMs = 0;
    private long windowStartMs = 0;

    // Nodes isolated by incident response; refreshed every tick
    private Set<String> quarantinedIds = Set.of();
    private long blockedPackets = 0;

    // Realistic port distributions per node type
    private static final int[] WEB_PORTS = {80, 443, 8080, 8443};
    private static final int[] DB_PORTS = {3306, 5432, 27017, 6379};
    private static final int[] IOT_PORTS = {1883, 8883, 5683}; // MQTT, CoAP
    private static final String[] PROTOCOLS = {"TCP", "UDP", "HTTP", "HTTPS", "DNS"};

    // External-profile node IDs that act as attack sources
    // External attacker hosts live outside the network (ids start with "ext-"). Their traffic
    // enters via Internet Gateway -> Firewall -> Core Switch. Each attack campaign gets its own
    // hosts (a DDoS botnet, or one brute-force host) from documentation IP ranges.
    private static final int BOTNET_SIZE = 160;  // hosts available to one DDoS campaign
    private static final int BOTNET_WAVE = 8;    // hosts sending at once; blocked hosts are replaced
    private final java.util.Map<String, List<String>> campaignSources = new java.util.HashMap<>();
    // Internal PCs that can act as compromised sources
    private static final String[] INTERNAL_PCS = {"dev-pc", "emp-pc-1", "emp-pc-2", "admin-pc"};

    public TrafficGeneratorService(NetworkNodeRepository nodeRepository, FlowService flowService, AttackService attackService) {
        this.nodeRepository = nodeRepository;
        this.flowService = flowService;
        this.attackService = attackService;
    }

    public void generateTraffic() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        if (nodes.size() < 2) return;

        windowStartMs = simClockMs;
        simClockMs += WINDOW_MS;

        quarantinedIds = nodes.stream()
            .filter(n -> n.getStatus() == NodeStatus.QUARANTINED)
            .map(NetworkNode::getId)
            .collect(Collectors.toSet());

        // Active nodes eligible for normal traffic (quarantined nodes are isolated)
        List<NetworkNode> trafficPool = nodes.stream()
            .filter(n -> !quarantinedIds.contains(n.getId()))
            .toList();

        // Generate normal background traffic based on node profiles
        int numPackets = trafficPool.size() >= 2 ? random.nextInt(10) + 5 : 0;
        for (int i = 0; i < numPackets; i++) {
            NetworkNode src = trafficPool.get(random.nextInt(trafficPool.size()));
            NetworkNode dst = trafficPool.get(random.nextInt(trafficPool.size()));
            if (src.getId().equals(dst.getId())) continue;

            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(dst.getId());
            p.setProtocol(getProtocolForType(src.getType().name()));
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(getDestPortForType(dst.getType().name()));
            p.setBytes(getTypicalBytesForType(src.getType().name()));
            p.setLabel("NORMAL");

            emit(p);
        }

        // Generate attack traffic — runs actively whenever an attack is injected
        // Forget attacker hosts of campaigns that have ended
        campaignSources.keySet().removeIf(k -> !attackService.getActiveAttacks().containsKey(k.substring(k.indexOf(':') + 1)));

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

    private void generateDDoS(List<NetworkNode> nodes, String target) {
        // Current wave: the first hosts of the botnet that the firewall has not blocked yet
        List<String> wave = externalHosts("DDOS", target, BOTNET_SIZE, "198.51.100.").stream()
            .filter(h -> !flowService.isBlockedExternal(h))
            .limit(BOTNET_WAVE)
            .toList();
        for (int i = 0; i < 50; i++) {
            Packet p = new Packet();
            if (wave.isEmpty()) return; // whole botnet blocked by the firewall
            p.setSource(wave.get(random.nextInt(wave.size())));
            p.setDest(target);
            p.setProtocol(random.nextBoolean() ? "TCP" : "UDP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(80);
            p.setBytes(32 + random.nextInt(32));
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    private void generatePortScan(List<NetworkNode> nodes, String target) {
        String srcId = INTERNAL_PCS[random.nextInt(INTERNAL_PCS.length)];
        NetworkNode src = nodes.stream().filter(n -> n.getId().equals(srcId)).findFirst().orElse(nodes.get(0));
        
        int startPort = random.nextInt(1000);
        for (int port = startPort; port < startPort + 100; port++) {
            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(target);
            p.setProtocol("TCP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(port);
            p.setBytes(40 + random.nextInt(24));
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    private void generateBruteForce(List<NetworkNode> nodes, String target) {
        String srcId = externalHosts("BRUTE", target, 1, "203.0.113.").get(0);
        int destPort = random.nextBoolean() ? 22 : 3389;
        for (int i = 0; i < 30; i++) {
            Packet p = new Packet();
            p.setSource(srcId);
            p.setDest(target);
            p.setProtocol("TCP");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(destPort);
            p.setBytes(128 + random.nextInt(256));
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    private void generateMalwareBeaconing(List<NetworkNode> nodes, String target) {
        for (int i = 0; i < 5; i++) {
            Packet p = new Packet();
            p.setSource(target);
            p.setDest("inet-gw");
            p.setProtocol("HTTPS");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(443);
            p.setBytes(64 + random.nextInt(128));
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    private void generateDataExfiltration(List<NetworkNode> nodes, String target) {
        for (int i = 0; i < 10; i++) {
            Packet p = new Packet();
            p.setSource(target);
            p.setDest("inet-gw");
            p.setProtocol("HTTPS");
            p.setSourcePort(1024 + random.nextInt(60000));
            p.setDestPort(443);
            p.setBytes(8000 + random.nextInt(7000));
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    private void generateReconnaissance(List<NetworkNode> nodes, String target) {
        NetworkNode src = nodes.stream().filter(n -> n.getId().equals(target)).findFirst().orElse(nodes.get(0));
            
        List<NetworkNode> victims = nodes.stream()
            .filter(n -> n.getId().contains("srv") || n.getId().contains("db") || n.getId().contains("fw"))
            .limit(5)
            .toList();
            
        for (NetworkNode dst : victims) {
            Packet p = new Packet();
            p.setSource(src.getId());
            p.setDest(dst.getId());
            p.setProtocol("ICMP");
            p.setSourcePort(0);
            p.setDestPort(random.nextBoolean() ? 80 : 443);
            p.setBytes(64);
            p.setLabel("ATTACK");
            emit(p);
        }
    }

    /**
     * Single exit point for simulated packets. Quarantine is enforced here:
     * packets from or to a quarantined node are dropped (the node is isolated).
     */
    private void emit(Packet p) {
        if (quarantinedIds.contains(p.getSource()) || quarantinedIds.contains(p.getDest())
                || flowService.isBlockedExternal(p.getSource())) {
            blockedPackets++;
            return;
        }
        p.setTimestampMs(windowStartMs + random.nextInt(WINDOW_MS));
        flowService.receivePacket(p);
    }

    private List<String> externalHosts(String kind, String target, int count, String prefix) {
        return campaignSources.computeIfAbsent(kind + ":" + target, k -> {
            java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
            while (hosts.size() < count) hosts.add("ext-" + prefix + (2 + random.nextInt(250)));
            return new java.util.ArrayList<>(hosts);
        });
    }

    public long getBlockedPackets() {
        return blockedPackets;
    }

    // --- Node Profile Helpers ---

    private String getProtocolForType(String type) {
        return switch (type) {
            case "SERVER" -> PROTOCOLS[random.nextInt(3)];
            case "DATABASE" -> "TCP";
            case "IOT_DEVICE" -> random.nextBoolean() ? "UDP" : "TCP";
            case "FIREWALL", "ROUTER" -> PROTOCOLS[random.nextInt(PROTOCOLS.length)];
            default -> "TCP";
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
            case "IOT_DEVICE" -> 64 + random.nextInt(256);
            case "SERVER" -> 256 + random.nextInt(1200);
            case "DATABASE" -> 512 + random.nextInt(2000);
            default -> 128 + random.nextInt(1000);
        };
    }
}
