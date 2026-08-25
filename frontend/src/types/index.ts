export type NodeStatus = 
  | "HEALTHY"
  | "TRAINING"
  | "SYNCHRONIZING"
  | "SUSPICIOUS"
  | "UNDER_ATTACK"
  | "QUARANTINED";

export type AttackType = 
  | "DDOS"
  | "PORT_SCAN"
  | "MALWARE_BEACONING"
  | "BRUTE_FORCE"
  | "DATA_EXFILTRATION";

export interface SimulationNode {
  id: string;
  name: string;
  type: string;
  trustScore: number;
  status: NodeStatus;
  position: { x: number; y: number };
}

export interface NetworkFlow {
  id: string;
  sourceId: string;
  targetId: string;
  protocol: string;
  bytesSent: number;
  isAnomalous: boolean;
}

export interface SimulationEvent {
  timestamp: string;
  type: string;
  source: string;
  target: string;
  description: string;
  severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
}

export interface AttackConfig {
  type: AttackType;
  sourceNode: string;
  targetNode: string;
  intensity: number;
  duration: number;
}

export interface FederatedRound {
  roundNumber: number;
  participants: string[];
  modelVersion: string;
  accuracy: number;
}

export interface PacketAnimation {
  id: string;
  source: string;
  target: string;
  color: string;
  progress: number;
}

export interface SimulationState {
  nodes: SimulationNode[];
  flows: NetworkFlow[];
  events: SimulationEvent[];
  federatedRounds: FederatedRound[];
  isRunning: boolean;
  tick: number;
  simulationTime: number;
}
