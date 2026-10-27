package com.fedanomaly.backend;

import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeType;
import com.fedanomaly.backend.service.AttackPlanner;
import com.fedanomaly.backend.service.AttackPlanner.Plan;
import com.fedanomaly.backend.service.AttackPlanner.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttackPlannerTest {

    /** Same 17 nodes the DataSeeder creates. */
    static List<NetworkNode> nodes() {
        Object[][] spec = {
            {"inet-gw", NodeType.ROUTER}, {"fw-1", NodeType.FIREWALL}, {"core-sw", NodeType.ROUTER},
            {"web-1", NodeType.SERVER}, {"app-1", NodeType.SERVER}, {"db-1", NodeType.DATABASE},
            {"vpn-gw", NodeType.ROUTER}, {"admin-pc", NodeType.PC}, {"dev-pc", NodeType.PC},
            {"emp-pc-1", NodeType.PC}, {"emp-pc-2", NodeType.PC}, {"iot-gw", NodeType.ROUTER},
            {"iot-1", NodeType.IOT_DEVICE}, {"iot-2", NodeType.IOT_DEVICE}, {"iot-3", NodeType.IOT_DEVICE},
            {"agg-srv", NodeType.SERVER}, {"backup-srv", NodeType.SERVER}};
        List<NetworkNode> out = new ArrayList<>();
        for (Object[] s : spec) {
            NetworkNode n = new NetworkNode();
            n.setId((String) s[0]);
            n.setName((String) s[0]);
            n.setType((NodeType) s[1]);
            out.add(n);
        }
        return out;
    }

    @Test
    void plansAreDeterministic() {
        for (String type : List.of("DDOS", "PORT_SCAN", "BRUTE_FORCE", "MALWARE", "DATA_EXFILTRATION", "RECONNAISSANCE")) {
            for (NetworkNode t : nodes()) {
                assertEquals(AttackPlanner.plan(type, t.getId(), nodes()), AttackPlanner.plan(type, t.getId(), nodes()),
                    type + " on " + t.getId());
            }
        }
    }

    @Test
    void victimAttacksOnlyAffectTheSelectedTarget() {
        for (String type : List.of("DDOS", "PORT_SCAN", "BRUTE_FORCE")) {
            for (NetworkNode t : nodes()) {
                Plan p = AttackPlanner.plan(type, t.getId(), nodes());
                assertEquals(Role.VICTIM, p.role());
                assertEquals(List.of(t.getId()), p.destinations(), "no other victims for " + type);
                assertFalse(p.sources().contains(t.getId()), "a node does not attack itself");
            }
        }
    }

    @Test
    void internetSourcesOnlyReachExposedNodes() {
        Plan web = AttackPlanner.plan("DDOS", "web-1", nodes());
        assertTrue(web.fromInternet());
        assertTrue(web.sources().stream().allMatch(s -> s.startsWith("ext-")));

        Plan db = AttackPlanner.plan("DDOS", "db-1", nodes());
        assertFalse(db.fromInternet());
        assertEquals(List.of("iot-1", "iot-2", "iot-3"), db.sources(), "internal flood comes from IoT bots");

        Plan bruteDb = AttackPlanner.plan("BRUTE_FORCE", "db-1", nodes());
        assertEquals(List.of("emp-pc-1"), bruteDb.sources(), "internal lateral attacker");
        assertEquals(5432, bruteDb.servicePort());
    }

    @Test
    void bruteForceTargetsTheAuthenticationService() {
        assertEquals(3389, AttackPlanner.plan("BRUTE_FORCE", "dev-pc", nodes()).servicePort());
        assertEquals(22, AttackPlanner.plan("BRUTE_FORCE", "app-1", nodes()).servicePort());
        assertEquals(23, AttackPlanner.plan("BRUTE_FORCE", "iot-2", nodes()).servicePort());
        assertEquals(443, AttackPlanner.plan("BRUTE_FORCE", "vpn-gw", nodes()).servicePort());
        assertEquals("emp-pc-2", AttackPlanner.plan("BRUTE_FORCE", "emp-pc-1", nodes()).sources().get(0),
            "the compromised workstation is never the target itself");
    }

    @Test
    void compromiseAttacksMakeTheSelectedNodeTheSource() {
        for (String type : List.of("MALWARE", "DATA_EXFILTRATION", "RECONNAISSANCE")) {
            Plan p = AttackPlanner.plan(type, "emp-pc-1", nodes());
            assertEquals(Role.COMPROMISED, p.role());
            assertEquals(List.of("emp-pc-1"), p.sources());
        }
        assertEquals(List.of("inet-gw"), AttackPlanner.plan("MALWARE", "emp-pc-1", nodes()).destinations());
        assertEquals(List.of("agg-srv", "app-1", "backup-srv", "db-1", "fw-1", "web-1"),
            AttackPlanner.plan("RECONNAISSANCE", "emp-pc-1", nodes()).destinations(),
            "recon sweeps servers, database and firewall only");
    }
}
