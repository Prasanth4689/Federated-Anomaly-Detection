import { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client/dist/sockjs';

export interface Packet {
  source: string;
  dest: string;
  protocol: string;
  bytes: number;
  label: string;
  receivedAt?: number;
}

export interface NetworkFlow {
  sourceNode: string;
  destNode: string;
  protocol: string;
  durationMs: number;
  packetCount: number;
  totalBytes: number;
  label: string;
  anomalyScore?: number;
  receivedAt?: number;
}

export interface SimulationEvent {
  type: string;
  version?: number;
  participants?: number;
  message?: string;
  node?: string;
  explanation?: Record<string, number>;
  timestamp?: string;
  // new fields from backend
  anomalyScore?: number;
  anomalyCount?: number;
  totalFlows?: number;
  trafficStats?: Record<string, number>;
  attackType?: string;
  oldTrust?: number;
  newTrust?: number;
  delta?: number;
  reason?: string;
  status?: string;
  target?: string;
  accuracy?: number;
}

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export function useWebSocket() {
  const [packets, setPackets] = useState<Packet[]>([]);
  const [flows, setFlows] = useState<NetworkFlow[]>([]);
  const [events, setEvents] = useState<SimulationEvent[]>([]);
  const [nodeUpdates, setNodeUpdates] = useState<any[]>([]);
  const [simulationState, setSimulationState] = useState<any>(null);
  
  const clientRef = useRef<Client | null>(null);

  // Buffer for incoming messages to throttle state updates (fixes massive re-render lag)
  const packetBuffer = useRef<Packet[]>([]);
  const flowBuffer = useRef<NetworkFlow[]>([]);
  const eventBuffer = useRef<SimulationEvent[]>([]);

  useEffect(() => {
    // Throttled update loop (runs every 200ms instead of on every single message)
    const interval = setInterval(() => {
      if (packetBuffer.current.length > 0) {
        setPackets(prev => {
          const next = [...packetBuffer.current, ...prev].slice(0, 100);
          packetBuffer.current = []; // flush buffer
          return next;
        });
      }
      if (flowBuffer.current.length > 0) {
        setFlows(prev => {
          const next = [...flowBuffer.current, ...prev].slice(0, 50);
          flowBuffer.current = [];
          return next;
        });
      }
      if (eventBuffer.current.length > 0) {
        setEvents(prev => {
          const next = [...eventBuffer.current, ...prev].slice(0, 100);
          eventBuffer.current = [];
          return next;
        });
      }
    }, 200);

    // 1. Establish SockJS connection
    const socket = new SockJS(`${API_URL}/ws`);
    const client = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    client.onConnect = function () {
      client.subscribe('/topic/packets', (message) => {
        const packet: Packet = JSON.parse(message.body);
        packet.receivedAt = Date.now();
        packetBuffer.current.unshift(packet);
      });

      client.subscribe('/topic/flows', (message) => {
        const flow: NetworkFlow = JSON.parse(message.body);
        flow.receivedAt = Date.now();
        flowBuffer.current.unshift(flow);
      });

      client.subscribe('/topic/events', (message) => {
        const event: SimulationEvent = {
          ...JSON.parse(message.body),
          timestamp: new Date().toLocaleTimeString(),
        };
        eventBuffer.current.unshift(event);
      });

      client.subscribe('/topic/nodes', (message) => {
        const updatedNodes = JSON.parse(message.body);
        setNodeUpdates(updatedNodes);
      });

      client.subscribe('/topic/simulation', (message) => {
        const simState = JSON.parse(message.body);
        setSimulationState(simState);
      });
    };

    client.onStompError = function (frame) {
      console.error('Broker error: ' + frame.headers['message']);
    };

    client.activate();
    clientRef.current = client;

    return () => {
      clearInterval(interval);
      client.deactivate();
    };
  }, []);

  return { packets, flows, events, nodeUpdates, simulationState };
}
