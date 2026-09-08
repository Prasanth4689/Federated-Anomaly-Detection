import { useState, useMemo, useEffect, useCallback } from 'react';
import { ReactFlow, Background, Controls, MiniMap } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Activity, ShieldAlert, Database, Clock, Zap, RotateCcw } from 'lucide-react';
import { useWebSocket } from './hooks/useWebSocket';
import CustomNode from './components/topology/CustomNode';
import AnimatedEdge from './components/topology/AnimatedEdge';
import NodeDetailPanel from './components/topology/NodeDetailPanel';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const nodeTypes = { custom: CustomNode };
const edgeTypes = { animated: AnimatedEdge };

// Base topology graph
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
    fetch(`${API_URL}/api/nodes`)
      .then(r => r.json())
      .then((data: any[]) => setNodes(data.map(formatNode)))
      .catch(console.error);
  }, []);

  // Sync active attacks from events (no polling needed now that we have ATTACK_STARTED/STOPPED events)
  useEffect(() => {
    if (!events.length) return;
    const latestEvent = events[0];
    if (latestEvent.type === 'ATTACK_STARTED' && latestEvent.target && latestEvent.attackType) {
      setActiveAttacks(prev => ({ ...prev, [latestEvent.target as string]: latestEvent.attackType as string }));
    } else if (latestEvent.type === 'ATTACK_STOPPED' && latestEvent.target) {
      setActiveAttacks(prev => {
        const next = { ...prev };
        delete next[latestEvent.target as string];
        return next;
      });
    }
  }, [events]);

  // Initial fetch for attacks on load
  useEffect(() => {
    fetch(`${API_URL}/api/attacks/active`)
      .then(r => r.json())
      .then(setActiveAttacks)
      .catch(() => {});
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

  // Trigger re-render to fade out old packets (runs less frequently now that we throttle WebSocket)
  const [, forceRender] = useState(0);
  useEffect(() => {
    const t = setInterval(() => forceRender(v => v + 1), 1000);
    return () => clearInterval(t);
  }, []);

  // ── Controls ───────────────────────────────────────────────────────────────
  const simControl = useCallback((action: string) => {
    fetch(`${API_URL}/api/simulation/${action}`, { method: 'POST' })
      .then(r => r.json())
      .then(d => { setSimState(d.state); setSimSpeed(d.speed); })
      .catch(console.error);
  }, []);

  const setSpeed = useCallback((s: number) => {
    fetch(`${API_URL}/api/simulation/speed?multiplier=${s}`, { method: 'POST' })
      .then(r => r.json())
      .then(d => { setSimState(d.state); setSimSpeed(d.speed); })
      .catch(console.error);
  }, []);

  const handleAttackAction = useCallback(() => {
    if (!targetNode) return;
    const isActive = !!activeAttacks[targetNode];
    const url = isActive
      ? `${API_URL}/api/attacks/stop?target=${targetNode}`
      : `${API_URL}/api/attacks/inject?type=${attackType}&target=${targetNode}`;
    fetch(url, { method: 'POST' }).catch(console.error);
  }, [targetNode, attackType, activeAttacks]);

  const restoreAll = useCallback(() => {
    fetch(`${API_URL}/api/nodes/recoverAll`, { method: 'PUT' }).catch(console.error);
  }, []);

  // ── Derived data ───────────────────────────────────────────────────────────
  const fedRoundEvents  = useMemo(() => events.filter(e => e.type === 'FED_ROUND_COMPLETE'), [events]);
  const fedRounds       = fedRoundEvents.length;
  const currentRound    = fedRoundEvents.length > 0 ? (fedRoundEvents[0].version ?? fedRounds) : 0;
  const anomalyCount    = useMemo(() => events.filter(e => e.type === 'ANOMALY_DETECTED').length, [events]);
  const accuracy = useMemo(() => {
    if (fedRoundEvents.length > 0 && fedRoundEvents[0].accuracy) {
      return fedRoundEvents[0].accuracy; // Use real metric from backend if available
    }
    let base = 82.5;
    if (fedRounds > 0) base += Math.min(16.5, Math.log10(fedRounds + 1) * 8);
    const penalty = Math.min(anomalyCount * 0.1, 5);
    return Math.max(0, Math.min(99.9, base - penalty));
  }, [fedRoundEvents, fedRounds, anomalyCount]);

  const networkLoad = useMemo(() => {
    if (!flows.length) return 0;
    return flows.slice(0, 5).reduce((s, f) => s + f.totalBytes, 0) / 1024;
  }, [flows]);

  const quarantinedCount = useMemo(
    () => nodes.filter(n => n.data.status === 'QUARANTINED').length,
    [nodes]
  );
  const activeCount = nodes.length - quarantinedCount;

  // ── Topology aware edge routing (simplified for ReactFlow visualization) ──
  const getPathToCore = (nodeId: string): string => {
    if (nodeId === 'inet-gw') return 'fw-1';
    if (nodeId.startsWith('iot-') && nodeId !== 'iot-gw') return 'iot-gw';
    return 'core-sw'; // Everything else connects directly to core-sw
  };

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
        .filter(p => now - (p.receivedAt ?? now) < 2000)
        .slice(0, 80) // Limit visible packets to avoid clutter
        .forEach(p => {
          const srcNode = nodes.find(n => n.id === p.source);
          if (srcNode?.data.status === 'QUARANTINED') return;
          
          // Determine edge color/style based on label (which is set by ML flow processing now)
          const isAnomaly = p.label === 'ANOMALY';
          const isSus = p.label === 'ATTACK'; // Keep 'ATTACK' support just in case

          // Route packet through topology visually instead of drawing line across map
          // 1. Source to its nearest switch/gateway
          const hop1Target = getPathToCore(p.source);
          if (hop1Target !== p.dest) {
            const key1 = `e-${p.source}-${hop1Target}`;
            dynamic.set(key1, {
              id: key1,
              type: 'animated',
              source: p.source,
              target: hop1Target,
              data: { isAnomaly: isAnomaly || isSus },
            });
            
            // 2. From core switch to dest's nearest switch/gateway (if applicable)
            if (hop1Target === 'core-sw') {
               const hop2Source = getPathToCore(p.dest);
               if (hop2Source !== 'core-sw' && hop2Source !== p.dest) {
                  const key2 = `e-core-sw-${hop2Source}`;
                  dynamic.set(key2, {
                    id: key2,
                    type: 'animated',
                    source: 'core-sw',
                    target: hop2Source,
                    data: { isAnomaly: isAnomaly || isSus },
                  });
                  // 3. Final hop
                  const key3 = `e-${hop2Source}-${p.dest}`;
                  dynamic.set(key3, {
                    id: key3,
                    type: 'animated',
                    source: hop2Source,
                    target: p.dest,
                    data: { isAnomaly: isAnomaly || isSus },
                  });
               } else {
                 // 2. Switch to dest directly
                 const key2 = `e-core-sw-${p.dest}`;
                  dynamic.set(key2, {
                    id: key2,
                    type: 'animated',
                    source: 'core-sw',
                    target: p.dest,
                    data: { isAnomaly: isAnomaly || isSus },
                  });
               }
            }
          } else {
            // Direct connection
            const key = `e-${p.source}-${p.dest}`;
            dynamic.set(key, {
              id: key,
              type: 'animated',
              source: p.source,
              target: p.dest,
              data: { isAnomaly: isAnomaly || isSus },
            });
          }
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
      <header className="h-13 border-b border-soc-border bg-soc-panel flex items-center justify-between px-5 shrink-0 gap-4 shadow-sm z-10">
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
      <main className="flex-1 flex overflow-hidden relative">

        {/* Topology Canvas */}
        <section className="flex-1 relative border-r border-soc-border overflow-hidden bg-[#0f1420]">
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
          <div className="absolute top-4 left-4 bg-soc-panel/95 backdrop-blur-md border border-soc-border rounded-xl p-4 w-64 shadow-2xl z-10">
            <h3 className="flex items-center gap-2 text-red-400 font-bold mb-3 text-xs uppercase tracking-wider border-b border-soc-border pb-2">
              <Zap size={14} /> Attack Injection
            </h3>
            <div className="space-y-3">
              <Field label="Target Node">
                <select
                  className="w-full bg-soc-bg border border-soc-border p-1.5 rounded text-soc-text outline-none focus:border-red-500 text-xs transition-colors"
                  value={targetNode}
                  onChange={e => setTargetNode(e.target.value)}
                >
                  <option value="">Select target…</option>
                  {nodes.filter(n => n.id !== 'inet-gw').map(n => (
                    <option key={n.id} value={n.id}>{n.data.label} ({n.id})</option>
                  ))}
                </select>
              </Field>

              <Field label="Attack Type">
                <select
                  className="w-full bg-soc-bg border border-soc-border p-1.5 rounded text-soc-text outline-none focus:border-red-500 text-xs transition-colors"
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
                className={`w-full py-2 rounded-lg transition-all font-bold text-xs shadow-lg
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
                <div className="text-[10px] text-red-400/80 border border-red-900/40 bg-red-950/30 rounded p-2 space-y-1 mt-2">
                  <div className="font-bold text-[9px] uppercase tracking-widest text-red-500 mb-1">Active Attacks:</div>
                  {Object.entries(activeAttacks).map(([t, a]) => (
                    <div key={t} className="font-mono flex justify-between">
                      <span>{a}</span><span>→ {t}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </section>

        {/* Right Panel */}
        <aside className="w-80 flex flex-col bg-soc-panel shrink-0 shadow-[-10px_0_20px_rgba(0,0,0,0.2)] z-10 relative">

          {/* KPI Grid */}
          <div className="border-b border-soc-border p-4 shrink-0 bg-soc-panel/50">
            <h3 className="flex items-center gap-2 text-soc-muted font-bold mb-3 uppercase text-xs tracking-wider">
              <Activity size={14} /> Live KPIs
            </h3>
            <div className="grid grid-cols-2 gap-2">
              <KpiCard label="Global Accuracy" value={packets.length > 0 ? `${(accuracy ?? 0).toFixed(1)}%` : '—'}
                valueClass={(accuracy ?? 0) >= 90 ? 'text-emerald-400' : (accuracy ?? 0) >= 70 ? 'text-amber-400' : 'text-red-400'} />
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

          {/* Event Log (Timeline) */}
          <div className="flex-1 p-4 flex flex-col overflow-hidden relative">
            {/* Timeline track line */}
            <div className="absolute left-7 top-[3.5rem] bottom-4 w-px bg-soc-border/50 z-0 hidden lg:block" />
            
            <div className="flex items-center justify-between mb-3 shrink-0 relative z-10">
              <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-xs tracking-wider">
                <Clock size={14} /> Event Timeline
              </h3>
              <button
                onClick={() => window.open(`${API_URL}/api/flows/export`, '_blank')}
                className="text-[10px] px-2 py-1 rounded bg-soc-bg border border-soc-border hover:text-cyan-400 hover:border-cyan-400 transition-colors text-soc-muted"
              >
                EXPORT CSV
              </button>
            </div>

            <div className="flex-1 overflow-y-auto space-y-2 pr-1 relative z-10 pb-4">
              {events.map((log, i) => {
                const isFed  = log.type === 'FED_ROUND_COMPLETE';
                const isAtk  = log.type === 'ANOMALY_DETECTED';
                const isMitg = log.type === 'MITIGATION';
                const isTrst = log.type === 'TRUST_CHANGE';
                const isStrt = log.type === 'ATTACK_STARTED';
                
                return (
                  <div
                    key={i}
                    className={`flex flex-col gap-1 text-xs p-2.5 rounded-lg border-l-2 shadow-sm transition-all hover:translate-x-1 ${
                      isFed  ? 'bg-cyan-950/20 border-cyan-500 hover:bg-cyan-950/30'   :
                      isAtk  ? 'bg-red-950/20 border-red-500 hover:bg-red-950/30'    :
                      isMitg ? 'bg-amber-950/20 border-amber-500 hover:bg-amber-950/30' :
                      isTrst ? 'bg-emerald-950/10 border-emerald-500/50 hover:bg-emerald-950/20' :
                      isStrt ? 'bg-orange-950/20 border-orange-500 hover:bg-orange-950/30' :
                               'bg-soc-bg border-soc-border hover:bg-white/5'
                    }`}
                  >
                    <div className="flex justify-between items-start gap-2">
                      <span className={`font-bold text-[10px] px-1.5 py-0.5 rounded-sm uppercase tracking-wider
                        ${isFed ? 'bg-cyan-900/30 text-cyan-400' : 
                          isAtk ? 'bg-red-900/30 text-red-400' : 
                          isMitg ? 'bg-amber-900/30 text-amber-400' :
                          isTrst ? 'bg-emerald-900/30 text-emerald-400' :
                          isStrt ? 'bg-orange-900/30 text-orange-400' :
                          'bg-soc-border text-soc-muted'}`}>
                        {log.type.replace('_', ' ')}
                      </span>
                      <span className="text-soc-muted shrink-0 text-[9px] font-mono mt-0.5 opacity-70">
                        {log.timestamp}
                      </span>
                    </div>
                    
                    <div className="text-soc-text text-[11px] leading-tight mt-1 opacity-90">
                      {log.message}
                    </div>
                    
                    {log.node && (
                       <div className="mt-1 flex items-center gap-1.5 text-[9px] font-mono text-soc-muted">
                         <span className="w-1.5 h-1.5 rounded-full bg-current opacity-50" />
                         Target: <span className="text-soc-text font-bold">{log.node}</span>
                       </div>
                    )}
                    
                    {/* ML Explanation Data */}
                    {log.explanation && (
                      <div className="mt-2 bg-black/30 border border-soc-border/50 rounded p-1.5">
                        <div className="text-[9px] text-soc-muted uppercase mb-1">XAI Contribution:</div>
                        <div className="flex flex-wrap gap-1">
                          {Object.entries(log.explanation).slice(0, 3).map(([k, v]) => (
                            <span key={k} className="px-1 py-0.5 bg-red-900/20 text-red-300 rounded border border-red-900/30 text-[9px] font-mono flex items-center gap-1">
                              <span>{k}</span>
                              <span className="opacity-50">|</span>
                              <span className="font-bold">{(v as number).toFixed(2)}</span>
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                    
                    {/* Traffic Context for Anomalies */}
                    {log.trafficStats && (
                       <div className="mt-1.5 flex gap-3 text-[9px] font-mono text-soc-muted bg-black/20 p-1 rounded">
                         <span>pps: {(log.trafficStats['avgPacketsPerSec'] as number).toFixed(0)}</span>
                         <span>bps: {(log.trafficStats['avgBytesPerSec'] as number).toFixed(0)}</span>
                       </div>
                    )}
                  </div>
                );
              })}
              {events.length === 0 && (
                <div className="text-soc-muted text-xs italic text-center pt-6 flex flex-col items-center gap-2 opacity-50">
                   <Clock size={24} />
                   <span>Waiting for simulation events…</span>
                </div>
              )}
            </div>
          </div>
        </aside>
      </main>

      {/* Bottom: Live Packet Table */}
      <footer className="h-36 border-t border-soc-border bg-soc-panel shrink-0 flex flex-col shadow-[0_-5px_15px_rgba(0,0,0,0.1)] relative z-20">
        <div className="flex items-center justify-between px-4 py-2 border-b border-soc-border bg-black/10">
          <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-xs tracking-wider">
            <Database size={13} /> Live Packet Feed
          </h3>
          <div className="flex items-center gap-4 text-[10px] text-soc-muted">
             <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-full bg-emerald-500"></span> Normal Traffic</span>
             <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-full bg-red-500"></span> ML Anomaly</span>
             <span>{packets.length} packets buffered</span>
          </div>
        </div>
        <div className="flex-1 overflow-auto bg-soc-bg/50">
          <table className="w-full text-left text-xs">
            <thead className="bg-black/40 text-soc-muted sticky top-0 backdrop-blur-sm z-10 shadow-sm">
              <tr>
                <th className="px-4 py-1.5 font-medium">Timestamp</th>
                <th className="px-4 py-1.5 font-medium">Source</th>
                <th className="px-4 py-1.5 font-medium">Destination</th>
                <th className="px-4 py-1.5 font-medium">Protocol</th>
                <th className="px-4 py-1.5 font-medium text-right">Payload (Bytes)</th>
                <th className="px-4 py-1.5 font-medium">ML Classification</th>
              </tr>
            </thead>
            <tbody>
              {packets.slice(0, 50).map((p, i) => {
                const isAnomaly = p.label === 'ANOMALY' || p.label === 'ATTACK';
                return (
                  <tr key={i} className={`border-t border-soc-border/30 hover:bg-white/5 transition-colors ${isAnomaly ? 'bg-red-950/10' : ''}`}>
                    <td className="px-4 py-1 text-soc-muted font-mono text-[10px]">{new Date(p.receivedAt ?? Date.now()).toISOString().split('T')[1].slice(0,-1)}</td>
                    <td className="px-4 py-1 font-mono">{p.source}</td>
                    <td className="px-4 py-1 font-mono">{p.dest}</td>
                    <td className="px-4 py-1 text-cyan-400 font-mono text-[11px] font-bold">{p.protocol}</td>
                    <td className="px-4 py-1 text-right font-mono text-soc-muted">{p.bytes}</td>
                    <td className={`px-4 py-1 font-bold text-[10px] uppercase tracking-wider ${isAnomaly ? 'text-red-400' : 'text-emerald-400/80'}`}>
                      {p.label === 'ATTACK' ? 'ANOMALY' : p.label}
                    </td>
                  </tr>
                );
              })}
              {packets.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-soc-muted italic">
                    Start the simulation to capture live traffic…
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
      <label className="text-[10px] text-soc-muted uppercase tracking-wider font-bold">{label}</label>
      {children}
    </div>
  );
}

function KpiCard({ label, value, valueClass = 'text-soc-text' }: { label: string; value: any; valueClass?: string }) {
  return (
    <div className="bg-soc-bg border border-soc-border rounded-lg p-3 transition-colors hover:border-cyan-900/50 group">
      <div className="text-[10px] text-soc-muted mb-1 uppercase tracking-wider group-hover:text-cyan-500/70 transition-colors">{label}</div>
      <div className={`text-lg font-bold tabular-nums leading-none ${valueClass}`}>{value}</div>
    </div>
  );
}

export default App;
