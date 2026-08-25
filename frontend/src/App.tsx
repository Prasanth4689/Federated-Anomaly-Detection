import { useState, useMemo, useEffect, useCallback } from 'react';
import { ReactFlow, Background, Controls, MiniMap } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Activity, ShieldAlert, Database, Clock, Zap, RotateCcw } from 'lucide-react';
import { useWebSocket } from './hooks/useWebSocket';
import CustomNode from './components/topology/CustomNode';
import AnimatedEdge from './components/topology/AnimatedEdge';
import NodeDetailPanel from './components/topology/NodeDetailPanel';

const nodeTypes = { custom: CustomNode };
const edgeTypes = { animated: AnimatedEdge };

const STATIC_LINKS = [
  { source: 'inet-gw',   target: 'fw-1' },
  { source: 'fw-1',      target: 'core-sw' },
  { source: 'core-sw',   target: 'web-1' },
  { source: 'core-sw',   target: 'app-1' },
  { source: 'core-sw',   target: 'db-1' },
  { source: 'core-sw',   target: 'vpn-gw' },
  { source: 'core-sw',   target: 'admin-pc' },
  { source: 'core-sw',   target: 'dev-pc' },
  { source: 'core-sw',   target: 'emp-pc-1' },
  { source: 'core-sw',   target: 'emp-pc-2' },
  { source: 'core-sw',   target: 'iot-gw' },
  { source: 'core-sw',   target: 'agg-srv' },
  { source: 'core-sw',   target: 'backup-srv' },
  { source: 'iot-gw',    target: 'iot-1' },
  { source: 'iot-gw',    target: 'iot-2' },
  { source: 'iot-gw',    target: 'iot-3' },
];

function formatNode(n: any) {
  return {
    id: n.id,
    type: 'custom',
    position: { x: n.posX, y: n.posY },
    data: {
      label:      n.name,
      type:       n.type.toLowerCase(),
      trustScore: n.trustScore,
      status:     n.status,
    },
  };
}

function App() {
  const [nodes, setNodes]               = useState<any[]>([]);
  const [selectedNode, setSelectedNode] = useState<any>(null);
  const [simState, setSimState]         = useState('STOPPED');
  const [simSpeed, setSimSpeed]         = useState(1);
  const [attackType, setAttackType]     = useState('DDOS');
  const [targetNode, setTargetNode]     = useState('');
  const [activeAttacks, setActiveAttacks] = useState<Record<string, string>>({});

  const { packets, flows, events, nodeUpdates, simulationState } = useWebSocket();

  // ── Bootstrap ──────────────────────────────────────────────────────────────
  useEffect(() => {
    fetch('http://localhost:8080/api/nodes')
      .then(r => r.json())
      .then((data: any[]) => setNodes(data.map(formatNode)))
      .catch(console.error);
  }, []);

  useEffect(() => {
    const iv = setInterval(() => {
      fetch('http://localhost:8080/api/attacks/active')
        .then(r => r.json())
        .then(setActiveAttacks)
        .catch(() => {});
    }, 2000);
    return () => clearInterval(iv);
  }, []);

  // ── Live updates ───────────────────────────────────────────────────────────
  useEffect(() => {
    if (nodeUpdates?.length > 0) setNodes(nodeUpdates.map(formatNode));
  }, [nodeUpdates]);

  useEffect(() => {
    if (simulationState) {
      setSimState(simulationState.state);
      setSimSpeed(simulationState.speed);
    }
  }, [simulationState]);

  // periodic re-render so stale animated edges disappear
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick(v => v + 1), 500);
    return () => clearInterval(t);
  }, []);

  // ── Controls ───────────────────────────────────────────────────────────────
  const simControl = useCallback((action: string) => {
    fetch(`http://localhost:8080/api/simulation/${action}`, { method: 'POST' })
      .then(r => r.json())
      .then(d => { setSimState(d.state); setSimSpeed(d.speed); })
      .catch(console.error);
  }, []);

  const setSpeed = useCallback((s: number) => {
    fetch(`http://localhost:8080/api/simulation/speed?multiplier=${s}`, { method: 'POST' })
      .then(r => r.json())
      .then(d => { setSimState(d.state); setSimSpeed(d.speed); })
      .catch(console.error);
  }, []);

  const handleAttackAction = useCallback(() => {
    if (!targetNode) return;
    const isActive = !!activeAttacks[targetNode];
    const url = isActive
      ? `http://localhost:8080/api/attacks/stop?target=${targetNode}`
      : `http://localhost:8080/api/attacks/inject?type=${attackType}&target=${targetNode}`;
    fetch(url, { method: 'POST' })
      .then(() => {
        setActiveAttacks(prev => {
          const next = { ...prev };
          if (isActive) delete next[targetNode]; else next[targetNode] = attackType;
          return next;
        });
      })
      .catch(console.error);
  }, [targetNode, attackType, activeAttacks]);

  const restoreAll = useCallback(() => {
    fetch('http://localhost:8080/api/nodes/recoverAll', { method: 'PUT' }).catch(console.error);
  }, []);

  // ── Derived data ───────────────────────────────────────────────────────────
  const fedRoundEvents  = useMemo(() => events.filter(e => e.type === 'FED_ROUND_COMPLETE'), [events]);
  const fedRounds       = fedRoundEvents.length;
  const currentRound    = fedRoundEvents.length > 0 ? (fedRoundEvents[0].version ?? fedRounds) : 0;
  const anomalyCount    = useMemo(() => events.filter(e => e.type === 'ANOMALY_DETECTED').length, [events]);
  const accuracy = useMemo(() => {
    let base = 82.5;
    if (fedRounds > 0) base += Math.min(16.5, Math.log10(fedRounds + 1) * 8);
    const penalty = Math.min(anomalyCount * 0.1, 5);
    return Math.max(0, Math.min(99.9, base - penalty));
  }, [fedRounds, anomalyCount]);

  const networkLoad = useMemo(() => {
    if (!flows.length) return 0;
    return flows.slice(0, 5).reduce((s, f) => s + f.totalBytes, 0) / 1024;
  }, [flows]);

  const quarantinedCount = useMemo(
    () => nodes.filter(n => n.data.status === 'QUARANTINED').length,
    [nodes]
  );
  const activeCount = nodes.length - quarantinedCount;

  // ── Edges ──────────────────────────────────────────────────────────────────
  const edges = useMemo(() => {
    const base = STATIC_LINKS.map(l => ({
      id:       `base-${l.source}-${l.target}`,
      source:   l.source,
      target:   l.target,
      type:     'default',
      animated: false,
      style:    { stroke: '#1f2937', strokeWidth: 1.5, opacity: 0.5 },
    }));

    const dynamic = new Map<string, any>();
    if (simState === 'RUNNING') {
      const now = Date.now();
      packets
        .filter(p => now - (p.receivedAt ?? now) < 1500)
        .slice(0, 60)
        .forEach(p => {
          const src = nodes.find(n => n.id === p.source);
          if (src?.data.status === 'QUARANTINED') return;
          const key = `e-${p.source}-${p.dest}`;
          const prev = dynamic.get(key);
          dynamic.set(key, {
            id:     key,
            type:   'animated',
            source: p.source,
            target: p.dest,
            data:   { isAnomaly: (prev?.data.isAnomaly) || p.label === 'ATTACK' },
          });
        });
    }

    return [...base, ...Array.from(dynamic.values())];
  }, [packets, nodes, simState]);

  // ── Live selected node ──────────────────────────────────────────────────────
  const liveSelectedNode = selectedNode
    ? nodes.find(n => n.id === selectedNode.id) ?? selectedNode
    : null;

  // ── Render ─────────────────────────────────────────────────────────────────
  const simDot =
    simState === 'RUNNING' ? 'bg-emerald-500 animate-pulse' :
    simState === 'PAUSED'  ? 'bg-amber-400' : 'bg-gray-500';
  const simLabel =
    simState === 'RUNNING' ? 'text-emerald-400' :
    simState === 'PAUSED'  ? 'text-amber-400'   : 'text-gray-400';

  return (
    <div className="h-screen w-screen flex flex-col bg-soc-bg text-soc-text overflow-hidden font-mono text-sm">

      {/* ── Header ── */}
      <header className="h-13 border-b border-soc-border bg-soc-panel flex items-center justify-between px-5 shrink-0 gap-4">
        <div className="flex items-center gap-2">
          <ShieldAlert className="text-cyan-400 w-5 h-5" />
          <span className="text-base font-bold tracking-widest text-cyan-400 uppercase">
            Fed-SOC
          </span>
          <span className="hidden md:block text-soc-muted text-xs ml-1">
            Anomaly Detection Framework
          </span>
        </div>

        {/* Sim controls */}
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1 bg-soc-bg border border-soc-border rounded-lg px-2 py-1">
            <CtrlBtn label="▶" active={simState === 'RUNNING'} activeClass="bg-emerald-900/40 text-emerald-400" onClick={() => simControl('start')} disabled={simState === 'RUNNING'} />
            <CtrlBtn label="⏸" onClick={() => simControl('pause')} disabled={simState !== 'RUNNING'} />
            <CtrlBtn label="⏹" onClick={() => simControl('stop')}  disabled={simState === 'STOPPED'} />
            <div className="w-px h-4 bg-soc-border mx-1" />
            {[1, 2, 4].map(s => (
              <CtrlBtn key={s} label={`${s}x`} active={simSpeed === s} activeClass="bg-cyan-900/40 text-cyan-400" onClick={() => setSpeed(s)} />
            ))}
          </div>

          <div className={`flex items-center gap-1.5 text-xs ${simLabel}`}>
            <div className={`w-2 h-2 rounded-full ${simDot}`} />
            {simState}
          </div>

          <div className="px-2.5 py-1 bg-soc-bg border border-soc-border rounded text-soc-muted text-xs">
            Round #{currentRound}
          </div>

          <button
            onClick={restoreAll}
            className="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-900/20 hover:bg-emerald-900/40 border border-emerald-600 text-emerald-400 rounded text-xs font-bold transition-all hover:scale-105"
            title="Gradually restore all quarantined nodes"
          >
            <RotateCcw size={12} /> RESTORE ALL
          </button>
        </div>
      </header>

      {/* ── Main ── */}
      <main className="flex-1 flex overflow-hidden">

        {/* Topology Canvas */}
        <section className="flex-1 relative border-r border-soc-border overflow-hidden">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            colorMode="dark"
            onNodeClick={(_e, node) => setSelectedNode(node)}
            onPaneClick={() => setSelectedNode(null)}
            fitView
            fitViewOptions={{ padding: 0.15 }}
            minZoom={0.3}
          >
            <Background color="#1a2030" gap={20} size={1} />
            <Controls showInteractive={false} />
            <MiniMap
              nodeStrokeColor="#374151"
              nodeColor={(n) => {
                const s = n.data?.status;
                if (s === 'QUARANTINED') return '#374151';
                if (s === 'SUSPICIOUS' || s === 'UNDER_ATTACK') return '#ef4444';
                return '#10b981';
              }}
              maskColor="rgba(0,0,0,0.6)"
              style={{ background: '#0a0e17', border: '1px solid #1f2937' }}
            />
          </ReactFlow>

          {/* Node Detail Overlay */}
          {liveSelectedNode && (
            <NodeDetailPanel
              node={liveSelectedNode}
              onClose={() => setSelectedNode(null)}
              packets={packets}
              flows={flows}
              currentRound={currentRound}
            />
          )}

          {/* Attack Injection Panel */}
          <div className="absolute top-4 left-4 bg-soc-panel/92 backdrop-blur border border-soc-border rounded-xl p-4 w-60 shadow-xl z-10">
            <h3 className="flex items-center gap-2 text-red-400 font-bold mb-3 text-xs uppercase tracking-wider border-b border-soc-border pb-2">
              <Zap size={14} /> Attack Injection
            </h3>
            <div className="space-y-2.5">
              <Field label="Target Node">
                <select
                  className="w-full bg-soc-bg border border-soc-border p-1.5 rounded text-soc-text outline-none focus:border-red-500 text-xs"
                  value={targetNode}
                  onChange={e => setTargetNode(e.target.value)}
                >
                  <option value="">Select target…</option>
                  {nodes.map(n => (
                    <option key={n.id} value={n.id}>{n.data.label}</option>
                  ))}
                </select>
              </Field>

              <Field label="Attack Type">
                <select
                  className="w-full bg-soc-bg border border-soc-border p-1.5 rounded text-soc-text outline-none focus:border-red-500 text-xs"
                  value={attackType}
                  onChange={e => setAttackType(e.target.value)}
                >
                  <option value="DDOS">DDoS Flood</option>
                  <option value="PORT_SCAN">Port Scan</option>
                  <option value="BRUTE_FORCE">Brute Force</option>
                  <option value="MALWARE">Malware Beacon</option>
                  <option value="DATA_EXFILTRATION">Data Exfiltration</option>
                  <option value="RECONNAISSANCE">Reconnaissance</option>
                </select>
              </Field>

              <button
                onClick={handleAttackAction}
                disabled={!targetNode}
                className={`w-full py-2 rounded-lg transition-all font-bold text-xs
                  ${!targetNode
                    ? 'bg-soc-bg border border-soc-border text-soc-muted cursor-not-allowed'
                    : activeAttacks[targetNode]
                      ? 'bg-amber-900/40 hover:bg-amber-900/60 border border-amber-500 text-amber-400'
                      : 'bg-red-900/30 hover:bg-red-900/50 border border-red-600 text-red-400 hover:scale-[1.02]'
                  }`}
              >
                {activeAttacks[targetNode] ? '⏹ STOP ATTACK' : '⚡ LAUNCH'}
              </button>

              {Object.keys(activeAttacks).length > 0 && (
                <div className="text-[9px] text-red-400/70 border border-red-900/40 bg-red-950/20 rounded p-1.5">
                  {Object.entries(activeAttacks).map(([t, a]) => (
                    <div key={t} className="font-mono">⚠ {a} → {t}</div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </section>

        {/* Right Panel */}
        <aside className="w-80 flex flex-col bg-soc-panel shrink-0">

          {/* KPI Grid */}
          <div className="border-b border-soc-border p-4 shrink-0">
            <h3 className="flex items-center gap-2 text-soc-muted font-bold mb-3 uppercase text-xs tracking-wider">
              <Activity size={14} /> Live KPIs
            </h3>
            <div className="grid grid-cols-2 gap-2">
              <KpiCard label="Global Accuracy" value={packets.length > 0 ? `${accuracy.toFixed(1)}%` : '—'}
                valueClass={accuracy >= 90 ? 'text-emerald-400' : accuracy >= 70 ? 'text-amber-400' : 'text-red-400'} />
              <KpiCard label="Anomalies" value={anomalyCount}
                valueClass={anomalyCount > 0 ? 'text-red-400' : 'text-soc-muted'} />
              <KpiCard label="Active Nodes" value={`${activeCount} / ${nodes.length}`}
                valueClass="text-emerald-400" />
              <KpiCard label="Quarantined" value={quarantinedCount}
                valueClass={quarantinedCount > 0 ? 'text-red-400' : 'text-soc-muted'} />
              <KpiCard label="Fed Rounds" value={fedRounds} valueClass="text-cyan-400" />
              <KpiCard label="Net Load" value={`${networkLoad.toFixed(1)} KB/s`} />
            </div>
          </div>

          {/* Event Log */}
          <div className="flex-1 p-4 flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-3 shrink-0">
              <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-xs tracking-wider">
                <Clock size={14} /> Event Log
              </h3>
              <button
                onClick={() => window.open('http://localhost:8080/api/flows/export', '_blank')}
                className="text-[10px] px-2 py-1 rounded bg-soc-bg border border-soc-border hover:text-cyan-400 hover:border-cyan-400 transition-colors text-soc-muted"
              >
                EXPORT CSV
              </button>
            </div>

            <div className="flex-1 overflow-y-auto space-y-1.5 pr-1">
              {events.map((log, i) => {
                const isFed  = log.type === 'FED_ROUND_COMPLETE';
                const isAtk  = log.type === 'ANOMALY_DETECTED';
                return (
                  <div
                    key={i}
                    className={`flex flex-col gap-0.5 text-xs p-2 rounded-lg border-l-2 ${
                      isFed  ? 'bg-cyan-950/20 border-cyan-500'   :
                      isAtk  ? 'bg-red-950/20 border-red-500'    :
                               'bg-soc-bg border-soc-border'
                    }`}
                  >
                    <div className="flex gap-2 items-center">
                      <span className="text-soc-muted shrink-0 text-[10px]">{log.timestamp || new Date().toLocaleTimeString()}</span>
                      <span className={`font-bold text-[10px] ${isFed ? 'text-cyan-400' : isAtk ? 'text-red-400' : 'text-soc-muted'}`}>
                        {log.type}
                      </span>
                      {log.node && <span className="text-soc-muted text-[10px] ml-auto font-mono">{log.node}</span>}
                    </div>
                    {log.message && (
                      <div className="text-soc-text text-[10px] pl-1">{log.message}</div>
                    )}
                    {log.explanation && (
                      <div className="flex flex-wrap gap-1 mt-0.5">
                        {Object.entries(log.explanation).slice(0, 2).map(([k, v]) => (
                          <span key={k} className="px-1.5 py-0.5 bg-red-950/40 text-red-300 rounded border border-red-900/50 text-[9px] font-mono">
                            {k}: {(v as number).toFixed(2)}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
              {events.length === 0 && (
                <div className="text-soc-muted text-xs italic text-center pt-6">Waiting for events…</div>
              )}
            </div>
          </div>
        </aside>
      </main>

      {/* Bottom: Live Packet Table */}
      <footer className="h-36 border-t border-soc-border bg-soc-panel shrink-0 flex flex-col">
        <div className="flex items-center justify-between px-4 py-2 border-b border-soc-border">
          <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-xs tracking-wider">
            <Database size={13} /> Live Packet Feed
          </h3>
          <span className="text-[10px] text-soc-muted">{packets.length} packets buffered</span>
        </div>
        <div className="flex-1 overflow-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-black/30 text-soc-muted sticky top-0">
              <tr>
                <th className="px-3 py-1.5 font-medium">Time</th>
                <th className="px-3 py-1.5 font-medium">Source</th>
                <th className="px-3 py-1.5 font-medium">Dest</th>
                <th className="px-3 py-1.5 font-medium">Proto</th>
                <th className="px-3 py-1.5 font-medium">Bytes</th>
                <th className="px-3 py-1.5 font-medium">Label</th>
              </tr>
            </thead>
            <tbody>
              {packets.slice(0, 50).map((p, i) => (
                <tr key={i} className={`border-t border-soc-border/40 hover:bg-white/5 transition-colors ${p.label === 'ATTACK' ? 'bg-red-950/10' : ''}`}>
                  <td className="px-3 py-1 text-soc-muted font-mono">{new Date(p.receivedAt ?? Date.now()).toLocaleTimeString()}</td>
                  <td className="px-3 py-1 font-mono">{p.source}</td>
                  <td className="px-3 py-1 font-mono">{p.dest}</td>
                  <td className="px-3 py-1 text-cyan-400">{p.protocol}</td>
                  <td className="px-3 py-1">{p.bytes}</td>
                  <td className={`px-3 py-1 font-bold text-[10px] ${p.label === 'ATTACK' ? 'text-red-400' : 'text-emerald-400'}`}>
                    {p.label}
                  </td>
                </tr>
              ))}
              {packets.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-3 py-4 text-center text-soc-muted italic">
                    Start the simulation to see live traffic…
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </footer>
    </div>
  );
}

// ── Micro Components ──────────────────────────────────────────────────────────
function CtrlBtn({ label, active, activeClass, onClick, disabled }: any) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`px-2 py-0.5 rounded text-xs font-bold transition-all disabled:opacity-30
        ${active ? activeClass : 'text-soc-muted hover:text-soc-text hover:bg-white/5'}`}
    >
      {label}
    </button>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-[10px] text-soc-muted uppercase tracking-wider">{label}</label>
      {children}
    </div>
  );
}

function KpiCard({ label, value, valueClass = 'text-soc-text' }: { label: string; value: any; valueClass?: string }) {
  return (
    <div className="bg-soc-bg border border-soc-border rounded-lg p-2.5">
      <div className="text-[10px] text-soc-muted mb-0.5">{label}</div>
      <div className={`text-lg font-bold tabular-nums leading-none ${valueClass}`}>{value}</div>
    </div>
  );
}

export default App;
