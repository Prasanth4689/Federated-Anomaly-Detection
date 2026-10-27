package com.fedanomaly.backend.service;

import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeType;

import java.util.*;

/**
 * Deterministic, topology-aware attack rules: who attacks, who is affected, on which service.
 *
 * Modelled network: Internet -> Internet Gateway -> Main Firewall -> Core Switch, with servers,
 * database, workstations and the VPN gateway on the core, and IoT devices behind the IoT gateway.
 * Perimeter policy: only the edge devices, the VPN gateway and the DMZ web server accept traffic
 * from the internet; everything else is reachable from inside only.
 *
 * Victim attacks (DDoS, port scan, brute force) hit the selected node. Compromise attacks
 * (malware beaconing, exfiltration, reconnaissance) make the selected node the source.
 * Only traffic is generated from a plan; detection is left entirely to the ML model.
 */
public final class AttackPlanner {
    private AttackPlanner() {}

    public enum Role { VICTIM, COMPROMISED }

    public record Plan(String attackType, String target, Role role, List<String> sources,
                       List<String> destinations, int servicePort, String serviceName,
                       boolean fromInternet, String description) {}

    /** Internet-reachable nodes and the service the perimeter exposes for each. */
    static final Map<String, Integer> INTERNET_EXPOSED = Map.of(
        "inet-gw", 443, "fw-1", 443, "vpn-gw", 443, "web-1", 443);

    /** Workstations in the order they are assumed compromised (employee PCs are phished first). */
    static final List<String> WORKSTATIONS = List.of("emp-pc-1", "emp-pc-2", "dev-pc", "admin-pc");

    static final String EGRESS = "inet-gw";
    static final int BOTNET_SIZE = 160;

    public static Role roleOf(String attackType) {
        String t = normalise(attackType);
        return t.equals("MALWARE") || t.equals("EXFIL") || t.equals("RECON") ? Role.COMPROMISED : Role.VICTIM;
    }

    public static Plan plan(String attackType, String target, List<NetworkNode> nodes) {
        Map<String, NetworkNode> byId = new TreeMap<>();
        for (NetworkNode n : nodes) byId.put(n.getId(), n);
        NetworkNode t = byId.get(target);
        String type = normalise(attackType);
        String tName = t != null ? t.getName() : target;
        boolean exposed = INTERNET_EXPOSED.containsKey(target);

        switch (type) {
            case "DDOS": {
                int port = exposed ? INTERNET_EXPOSED.get(target) : servicePort(t);
                if (exposed) {
                    List<String> botnet = externalHosts("ddos:" + target, "198.51.100.", BOTNET_SIZE);
                    return new Plan(type, target, Role.VICTIM, botnet, List.of(target), port, serviceName(port), true,
                        "DDoS on " + tName + " (" + serviceName(port) + ") from an external botnet via Internet Gateway → Main Firewall");
                }
                // Not reachable from the internet: flood comes from compromised IoT devices inside (Mirai-style)
                List<String> bots = byId.values().stream()
                    .filter(n -> n.getType() == NodeType.IOT_DEVICE && !n.getId().equals(target))
                    .map(NetworkNode::getId).toList();
                return new Plan(type, target, Role.VICTIM, bots, List.of(target), port, serviceName(port), false,
                    "DDoS on " + tName + " (" + serviceName(port) + ") from compromised IoT devices " + bots
                        + " via IoT Gateway (target is not internet-facing)");
            }
            case "PORT": {
                String scanner = exposed ? externalHosts("scan:" + target, "192.0.2.", 1).get(0) : workstation(target, byId);
                return new Plan(type, target, Role.VICTIM, List.of(scanner), List.of(target), 0, "TCP ports", exposed,
                    "Port scan of " + tName + " from " + label(scanner, byId)
                        + (exposed ? " via the perimeter" : " (internal lateral movement)")
                        + " — connection attempts only, nothing is compromised by the scan");
            }
            case "BRUTE": {
                int port = exposed ? INTERNET_EXPOSED.get(target) : authPort(t);
                String attacker = exposed ? externalHosts("brute:" + target, "203.0.113.", 1).get(0) : workstation(target, byId);
                return new Plan(type, target, Role.VICTIM, List.of(attacker), List.of(target), port, serviceName(port), exposed,
                    "Brute-force login on " + tName + " " + serviceName(port) + " from " + label(attacker, byId)
                        + (exposed ? " via the perimeter" : " (internal lateral movement)"));
            }
            case "MALWARE":
                return new Plan(type, target, Role.COMPROMISED, List.of(target), List.of(EGRESS), 443, "HTTPS", false,
                    tName + " is infected: beaconing to an external C2 server out through Main Firewall → Internet Gateway");
            case "EXFIL":
                return new Plan(type, target, Role.COMPROMISED, List.of(target), List.of(EGRESS), 443, "HTTPS", false,
                    tName + " is compromised: exfiltrating data out through Main Firewall → Internet Gateway");
            case "RECON": {
                // Sweep of the infrastructure reachable from the compromised host
                List<String> sweep = byId.values().stream()
                    .filter(n -> !n.getId().equals(target))
                    .filter(n -> n.getType() == NodeType.SERVER || n.getType() == NodeType.DATABASE
                        || n.getType() == NodeType.FIREWALL)
                    .map(NetworkNode::getId).toList();
                return new Plan(type, target, Role.COMPROMISED, List.of(target), sweep, 0, "ICMP", false,
                    tName + " is compromised: ICMP sweep of reachable infrastructure " + sweep);
            }
            default:
                return new Plan(type, target, Role.VICTIM, List.of(), List.of(), 0, "", false, "Unknown attack " + attackType);
        }
    }

    static String normalise(String attackType) {
        String t = attackType == null ? "" : attackType.toUpperCase();
        if (t.startsWith("DDOS")) return "DDOS";
        if (t.startsWith("PORT")) return "PORT";
        if (t.startsWith("BRUTE")) return "BRUTE";
        if (t.startsWith("MALWARE")) return "MALWARE";
        if (t.startsWith("DATA") || t.startsWith("EXFIL")) return "EXFIL";
        if (t.startsWith("RECON")) return "RECON";
        return t;
    }

    /** The compromised internal workstation used for lateral attacks (never the target itself). */
    static String workstation(String target, Map<String, NetworkNode> byId) {
        return WORKSTATIONS.stream().filter(w -> !w.equals(target) && byId.containsKey(w)).findFirst()
            .orElse(WORKSTATIONS.get(0));
    }

    /** Login service an attacker would brute-force, by device type. */
    static int authPort(NetworkNode n) {
        if (n == null) return 22;
        return switch (n.getType()) {
            case PC, LAPTOP -> 3389;   // RDP
            case DATABASE -> 5432;     // database login
            case IOT_DEVICE -> 23;     // telnet
            default -> 22;             // SSH (servers, routers, firewall)
        };
    }

    /** Main service a flood would target, by device type. */
    static int servicePort(NetworkNode n) {
        if (n == null) return 443;
        return switch (n.getType()) {
            case DATABASE -> 5432;
            case IOT_DEVICE -> 1883;   // MQTT
            case PC, LAPTOP -> 445;    // SMB
            case ROUTER, FIREWALL -> 161; // SNMP / management plane
            default -> 443;
        };
    }

    static String serviceName(int port) {
        return switch (port) {
            case 22 -> "SSH :22"; case 23 -> "Telnet :23"; case 443 -> "HTTPS :443"; case 445 -> "SMB :445";
            case 161 -> "SNMP :161"; case 1883 -> "MQTT :1883"; case 3389 -> "RDP :3389"; case 5432 -> "DB :5432";
            default -> "port " + port;
        };
    }

    private static String label(String id, Map<String, NetworkNode> byId) {
        if (id.startsWith("ext-")) return "external host " + id.substring(4);
        NetworkNode n = byId.get(id);
        return n != null ? n.getName() + " (compromised)" : id;
    }

    /** Documentation-range IPs, seeded per campaign so the same attack always uses the same hosts. */
    static List<String> externalHosts(String campaign, String prefix, int count) {
        Random r = new Random(campaign.hashCode());
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        while (hosts.size() < count) hosts.add("ext-" + prefix + (2 + r.nextInt(250)));
        return new ArrayList<>(hosts);
    }
}
