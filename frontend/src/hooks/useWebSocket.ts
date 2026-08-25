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
}

export function useWebSocket() {
  const [packets, setPackets] = useState<Packet[]>([]);
  const [flows, setFlows] = useState<NetworkFlow[]>([]);
  const [events, setEvents] = useState<SimulationEvent[]>([]);
  const [nodeUpdates, setNodeUpdates] = useState<any[]>([]);
  const [simulationState, setSimulationState] = useState<any>(null);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const socket = new SockJS('http://localhost:8080/ws');
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
        setPackets(prev => [packet, ...prev].slice(0, 100));
      });

      client.subscribe('/topic/flows', (message) => {
        const flow: NetworkFlow = JSON.parse(message.body);
        flow.receivedAt = Date.now();
        setFlows(prev => [flow, ...prev].slice(0, 50));
      });

      client.subscribe('/topic/events', (message) => {
        const event: SimulationEvent = {
          ...JSON.parse(message.body),
          timestamp: new Date().toLocaleTimeString(),
        };
        setEvents(prev => [event, ...prev].slice(0, 100));
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
      client.deactivate();
    };
  }, []);

  return { packets, flows, events, nodeUpdates, simulationState };
}
