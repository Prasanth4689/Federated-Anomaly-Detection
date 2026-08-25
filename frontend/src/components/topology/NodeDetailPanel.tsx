import { X, Server, Activity, Database, Network, Shield, Monitor, HardDrive, Cpu, Router, RefreshCw } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { Packet, NetworkFlow } from '../../hooks/useWebSocket';

interface NodeDetailPanelProps {
  node: any;           // ReactFlow node — data lives in node.data
  onClose: () => void;
  packets: Packet[];
  flows: NetworkFlow[];
  currentRound: number;
}

const typeIcons: Record<string, any> = {
  server: Server,
  pc: Monitor,
  database: HardDrive,
  iot: Cpu,
  iot_device: Cpu,
  router: Router,
  firewall: Shield,
};

const statusColor: Record<string, string> = {
  HEALTHY:       'text-emerald-400',
  TRAINING:      'text-purple-400',
  SYNCHRONIZING: 'text-blue-400',
  SUSPICIOUS:    'text-amber-400',
  UNDER_ATTACK:  'text-red-400',
  QUARANTINED:   'text-gray-400',
};

export default function NodeDetailPanel({ node, onClose, packets, flows, currentRound }: NodeDetailPanelProps) {
  if (!node) return null;

  const [restoring, setRestoring] = useState(false);
  const data = node.data;
  const status: string = data.status || 'HEALTHY';
  const trust: number = data.trustScore ?? 100;

  const typeKey = (data.type || 'server').toLowerCase();
  const Icon = typeIcons[typeKey] || Server;

  // Fix: backend sends packets with `source`/`dest` fields
  const nodePackets = useMemo(
    () => packets.filter(p => p.source === node.id || p.dest === node.id),
    [packets, node.id]
  );

  const nodeFlows = useMemo(
    () => flows.filter(f => f.sourceNode === node.id || f.destNode === node.id),
    [flows, node.id]
  );

  const attackPackets  = useMemo(() => nodePackets.filter(p => p.label === 'ATTACK'), [nodePackets]);
  const normalPackets  = useMemo(() => nodePackets.filter(p => p.label === 'NORMAL'), [nodePackets]);

  const bytesIn  = useMemo(() => flows.filter(f => f.destNode   === node.id).reduce((s, f) => s + f.totalBytes, 0), [flows, node.id]);
  const bytesOut = useMemo(() => flows.filter(f => f.sourceNode === node.id).reduce((s, f) => s + f.totalBytes, 0), [flows, node.id]);

  const trustColor = trust < 30 ? 'bg-red-500' : trust < 70 ? 'bg-amber-400' : 'bg-emerald-500';
  const trustTextColor = trust < 30 ? 'text-red-400' : trust < 70 ? 'text-amber-400' : 'text-emerald-400';

  const handleRestore = () => {
    setRestoring(true);
    fetch(`http://localhost:8080/api/nodes/${node.id}/recover`, { method: 'PUT' })
      .catch(console.error)
      .finally(() => setTimeout(() => setRestoring(false), 2000));
  };

  return (
    <div className="absolute top-0 right-0 h-full w-80 bg-soc-panel/97 backdrop-blur-lg border-l border-soc-border shadow-2xl z-50 flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between p-4 border-b border-soc-border bg-black/20">
        <div className="flex items-center gap-3">
          <div className={`p-2 rounded-lg bg-black/30 ${statusColor[status] || 'text-cyan-400'}`}>
            <Icon size={18} />
          </div>
          <div>
            <h2 className="font-bold text-soc-accent tracking-wide text-sm">{data.label}</h2>
            <div className="text-[10px] text-soc-muted font-mono">{node.id} · {typeKey.toUpperCase()}</div>
          </div>
        </div>
        <button onClick={onClose} className="text-soc-muted hover:text-white transition-colors p-1 rounded hover:bg-white/10">
          <X size={16} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-4">

        {/* Trust Score */}
        <section className="bg-black/20 border border-soc-border rounded-xl p-3">
          <div className="flex justify-between items-center mb-2">
            <span className="text-[10px] text-soc-muted uppercase font-bold tracking-wider flex items-center gap-1">
              <Activity size={11} /> Trust Score
            </span>
            <span className={`text-xl font-bold tabular-nums ${trustTextColor}`}>{trust.toFixed(1)}</span>
          </div>
          <div className="w-full bg-black/40 rounded-full h-2 overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-700 ${trustColor}`}
              style={{ width: `${Math.max(0, Math.min(100, trust))}%` }}
            />
          </div>
          <div className="flex justify-between text-[9px] text-soc-muted mt-1">
            <span>0 — Quarantine</span>
            <span>100 — Trusted</span>
          </div>
        </section>

        {/* Status + Restore */}
        <section className="bg-black/20 border border-soc-border rounded-xl p-3 space-y-2">
          <div className="flex justify-between items-center">
            <span className="text-[10px] text-soc-muted uppercase font-bold tracking-wider">Status</span>
            <span className={`text-xs font-bold ${statusColor[status] || 'text-soc-text'}`}>{status}</span>
          </div>
          {status !== 'HEALTHY' && (
            <button
              onClick={handleRestore}
              disabled={restoring}
              className={`w-full py-1.5 text-xs font-bold rounded-lg border transition-all duration-200
                ${restoring
                  ? 'bg-emerald-900/30 border-emerald-700 text-emerald-600 cursor-wait'
                  : 'bg-emerald-900/20 hover:bg-emerald-900/50 border-emerald-500 text-emerald-400 hover:scale-[1.02]'
                }`}
            >
              <span className="flex items-center justify-center gap-1.5">
                <RefreshCw size={11} className={restoring ? 'animate-spin' : ''} />
                {restoring ? 'RESTORING…' : 'RESTORE NODE'}
              </span>
            </button>
          )}
        </section>

        {/* Federated Learning */}
        <section className="bg-black/20 border border-soc-border rounded-xl p-3 space-y-2">
          <h3 className="text-[10px] text-soc-muted uppercase font-bold tracking-wider flex items-center gap-1">
            <Database size={11} /> Local ML Model
          </h3>
          <div className="grid grid-cols-2 gap-2">
            <Stat label="Algorithm" value="Isolation Forest" />
            <Stat label="Fed Round" value={`#${currentRound}`} valueClass="text-cyan-400" />
            <Stat label="Model Ver." value={`v${currentRound}.0`} valueClass="text-cyan-400" />
            <Stat label="Status" value={status === 'QUARANTINED' ? 'Excluded' : 'Active'} valueClass={status === 'QUARANTINED' ? 'text-gray-500' : 'text-emerald-400'} />
          </div>
        </section>

        {/* Traffic Statistics */}
        <section className="bg-black/20 border border-soc-border rounded-xl p-3 space-y-2">
          <h3 className="text-[10px] text-soc-muted uppercase font-bold tracking-wider flex items-center gap-1">
            <Network size={11} /> Traffic (Last Buffer)
          </h3>
          <div className="grid grid-cols-2 gap-2">
            <Stat label="Total Packets" value={nodePackets.length} />
            <Stat label="Attack Pkts"   value={attackPackets.length}  valueClass={attackPackets.length > 0 ? 'text-red-400' : 'text-soc-text'} />
            <Stat label="Normal Pkts"   value={normalPackets.length}  valueClass="text-emerald-400" />
            <Stat label="Active Flows"  value={nodeFlows.length} />
            <Stat label="Bytes In"      value={`${(bytesIn / 1024).toFixed(1)} KB`} />
            <Stat label="Bytes Out"     value={`${(bytesOut / 1024).toFixed(1)} KB`} />
          </div>
        </section>

        {/* Recent attack packets */}
        {attackPackets.length > 0 && (
          <section className="bg-red-950/20 border border-red-900/50 rounded-xl p-3">
            <h3 className="text-[10px] text-red-400 uppercase font-bold tracking-wider mb-2 flex items-center gap-1">
              ⚠ Attack Traffic Detected
            </h3>
            <div className="space-y-1 max-h-32 overflow-y-auto">
              {attackPackets.slice(0, 8).map((p, i) => (
                <div key={i} className="flex justify-between text-[10px] text-red-300/80 font-mono">
                  <span>{p.source} → {p.dest}</span>
                  <span className="text-red-400">{p.protocol} · {p.bytes}B</span>
                </div>
              ))}
            </div>
          </section>
        )}

      </div>
    </div>
  );
}

function Stat({ label, value, valueClass = 'text-soc-text' }: { label: string; value: any; valueClass?: string }) {
  return (
    <div className="bg-black/20 rounded-lg px-2 py-1.5">
      <div className="text-[9px] text-soc-muted uppercase">{label}</div>
      <div className={`text-xs font-bold ${valueClass} truncate`}>{value}</div>
    </div>
  );
}
