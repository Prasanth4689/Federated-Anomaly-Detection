package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.model.Packet;
import com.fedanomaly.backend.repository.NetworkNodeRepository;

import java.util.List;
import java.util.Map;
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

    // External attacker hosts (ids "ext-<ip>", chosen by AttackPlanner) enter via the perimeter
    private static final int BOTNET_WAVE = 8;    // hosts sending at once; blocked hosts are replaced

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

        // Attack traffic follows the deterministic, topology-aware plan for each active attack
        for (Map.Entry<String, String> attack : attackService.getActiveAttacks().entrySet()) {
            AttackPlanner.Plan plan = AttackPlanner.plan(attack.getValue(), attack.getKey(), nodes);
            switch (plan.attackType()) {
                case "DDOS" -> generateDDoS(plan);
                case "PORT" -> generatePortScan(plan);
                case "BRUTE" -> generateBruteForce(plan);
                case "MALWARE" -> generateOutbound(plan, 5, 64, 128);       // small periodic beacons
                case "EXFIL" -> generateOutbound(plan, 10, 8000, 7000);     // large uploads
                case "RECON" -> generateReconnaissance(plan);
                default -> { }
            }
        }
    }

    private Packet attackPacket(String src, String dst, String protocol, int destPort, int bytes) {
        Packet p = new Packet();
        p.setSource(src);
        p.setDest(dst);
        p.setProtocol(protocol);
        p.setSourcePort(1024 + random.nextInt(60000));
        p.setDestPort(destPort);
        p.setBytes(bytes);
        p.setLabel("ATTACK"); // simulation ground truth only; never sent to the model
        return p;
    }

    private void generateDDoS(AttackPlanner.Plan plan) {
        // External botnets attack in waves; hosts the firewall has blocked are replaced by the next ones
        List<String> senders = plan.fromInternet()
            ? plan.sources().stream().filter(h -> !flowService.isBlockedExternal(h)).limit(BOTNET_WAVE).toList()
            : plan.sources();
        if (senders.isEmpty()) return; // fully mitigated
        for (int i = 0; i < 50; i++) {
            String src = senders.get(random.nextInt(senders.size()));
            emit(attackPacket(src, plan.target(), random.nextBoolean() ? "TCP" : "UDP", plan.servicePort(), 32 + random.nextInt(32)));
        }
    }

    private void generatePortScan(AttackPlanner.Plan plan) {
        String scanner = plan.sources().get(0);
        int startPort = random.nextInt(1000);
        for (int port = startPort; port < startPort + 100; port++) {
            emit(attackPacket(scanner, plan.target(), "TCP", port, 40 + random.nextInt(24)));
        }
    }

    private void generateBruteForce(AttackPlanner.Plan plan) {
        String attacker = plan.sources().get(0);
        for (int i = 0; i < 30; i++) {
            emit(attackPacket(attacker, plan.target(), "TCP", plan.servicePort(), 128 + random.nextInt(256)));
        }
    }

    private void generateOutbound(AttackPlanner.Plan plan, int count, int minBytes, int spread) {
        for (int i = 0; i < count; i++) {
            emit(attackPacket(plan.target(), plan.destinations().get(0), "HTTPS", plan.servicePort(), minBytes + random.nextInt(spread)));
        }
    }

    private void generateReconnaissance(AttackPlanner.Plan plan) {
        for (String dst : plan.destinations()) {
            Packet p = attackPacket(plan.target(), dst, "ICMP", random.nextBoolean() ? 80 : 443, 64);
            p.setSourcePort(0);
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
