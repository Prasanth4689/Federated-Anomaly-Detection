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

export default function App() {
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

  // Sync active attacks from events
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

  // Trigger re-render to fade out old packets
  const [, forceRender] = useState(0);
  useEffect(() => {
    const t = setInterval(() => forceRender(v => v + 1), 500);
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
      return fedRoundEvents[0].accuracy;
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

  // ── High-Performance Aggregated Flow Routing ────────────────────────────────
  const getPathSegments = (src: string, dst: string): string[] => {
    // Treat external attackers as coming from the internet gateway
    if (src.startsWith('ext-') || src === 'EXTERNAL') src = 'inet-gw';
    if (dst.startsWith('ext-') || dst === 'EXTERNAL') dst = 'inet-gw';
    if (src === dst) return [];
    
    const getPathToCoreList = (node: string): string[] => {
        if (node === 'core-sw') return ['core-sw'];
        if (node === 'fw-1') return ['fw-1', 'core-sw'];
        if (node === 'inet-gw') return ['inet-gw', 'fw-1', 'core-sw'];
        if (node.startsWith('iot-') && node !== 'iot-gw') return [node, 'iot-gw', 'core-sw'];
        return [node, 'core-sw']; 
    };
    
    const srcPath = getPathToCoreList(src);
    const dstPath = getPathToCoreList(dst);
    
    const segments: string[] = [];
    for (let i = 0; i < srcPath.length - 1; i++) {
        segments.push(`${srcPath[i]}-${srcPath[i+1]}`);
        segments.push(`${srcPath[i+1]}-${srcPath[i]}`);
    }
    for (let i = 0; i < dstPath.length - 1; i++) {
        segments.push(`${dstPath[i]}-${dstPath[i+1]}`);
        segments.push(`${dstPath[i+1]}-${dstPath[i]}`);
    }
    return segments;
  };

  const edges = useMemo(() => {
    const now = Date.now();
    
    // 1. Aggregate recent packets onto physical links
    const linkStats: Record<string, { count: number; hasAnomaly: boolean }> = {};
    
    STATIC_LINKS.forEach(link => {
      linkStats[`${link.source}-${link.target}`] = { count: 0, hasAnomaly: false };
    });

    if (simState === 'RUNNING') {
      const activePackets = packets.filter(p => now - (p.receivedAt ?? now) < 1500);
      
      activePackets.forEach(p => {
        const srcNode = nodes.find(n => n.id === p.source);
        if (srcNode?.data.status === 'QUARANTINED') return;
        
        const isAnomaly = p.label === 'ANOMALY' || p.label === 'ATTACK';
        const segments = getPathSegments(p.source, p.dest);
        
        segments.forEach(seg => {
           if (linkStats[seg] !== undefined) {
             linkStats[seg].count += 1;
             if (isAnomaly) linkStats[seg].hasAnomaly = true;
           }
        });
      });
    }

    // 2. Render strictly 16 static edges with dynamic styling
    return STATIC_LINKS.map(link => {
      const key = `${link.source}-${link.target}`;
      const stats = linkStats[key];
      const isActive = stats.count > 0;
      
      return {
        id: `e-${key}`,
        source: link.source,
        target: link.target,
        type: 'animated', // Uses our new CSS-animated flow edge
        data: {
          isActive: isActive,
          isAnomaly: stats.hasAnomaly,
          volume: Math.min(1, stats.count / 30), // Normalize volume for thickness
        }
      };
    });
  }, [packets, nodes, simState]);

  const liveSelectedNode = selectedNode
    ? nodes.find(n => n.id === selectedNode.id) ?? selectedNode
    : null;

  return (
    <div className="h-screen w-screen flex flex-col bg-soc-bg text-soc-text overflow-hidden font-mono text-sm">

      {/* ── Top Layer: Global Federated Dashboard ── */}
      <header className="h-14 border-b border-soc-border bg-gradient-to-r from-soc-panel to-soc-bg flex items-center justify-between px-5 shrink-0 gap-4 shadow-md z-20">
        <div className="flex items-center gap-3">
          <div className="p-1.5 bg-cyan-950/50 rounded-lg border border-cyan-900/50">
             <ShieldAlert className="text-cyan-400 w-5 h-5" />
          </div>
          <div>
            <div className="text-sm font-bold tracking-widest text-cyan-400 uppercase leading-none">Fed-SOC</div>
            <div className="text-[10px] text-soc-muted uppercase tracking-wider mt-0.5">Federated Anomaly Detection</div>
          </div>
        </div>

        <div className="flex items-center gap-6">
          {/* Global ML Stats */}
          <div className="hidden md:flex items-center gap-4 bg-black/20 border border-soc-border rounded-lg px-4 py-1.5">
            <div className="flex flex-col">
              <span className="text-[9px] text-soc-muted uppercase">Global Model</span>
              <span className="text-xs font-bold text-cyan-400">v{currentRound}.0 (Round #{currentRound})</span>
            </div>
            <div className="w-px h-6 bg-soc-border"></div>
            <div className="flex flex-col">
              <span className="text-[9px] text-soc-muted uppercase">Global Accuracy</span>
              <span className={`text-xs font-bold ${accuracy >= 90 ? 'text-emerald-400' : 'text-amber-400'}`}>{(accuracy ?? 0).toFixed(1)}%</span>
            </div>
          </div>

          {/* Simulation Controls */}
          <div className="flex items-center gap-1 bg-soc-bg border border-soc-border rounded-lg p-1">
            <CtrlBtn label="▶" active={simState === 'RUNNING'} activeClass="bg-emerald-900/40 text-emerald-400" onClick={() => simControl('start')} disabled={simState === 'RUNNING'} />
            <CtrlBtn label="⏸" onClick={() => simControl('pause')} disabled={simState !== 'RUNNING'} />
            <CtrlBtn label="⏹" onClick={() => simControl('stop')}  disabled={simState === 'STOPPED'} />
            <div className="w-px h-4 bg-soc-border mx-1" />
            {[1, 2, 4].map(s => (
              <CtrlBtn key={s} label={`${s}x`} active={simSpeed === s} activeClass="bg-cyan-900/40 text-cyan-400" onClick={() => setSpeed(s)} />
            ))}
          </div>

          <button
            onClick={restoreAll}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-900/20 hover:bg-emerald-900/40 border border-emerald-600 text-emerald-400 rounded-lg text-xs font-bold transition-all hover:scale-105"
            title="Gradually restore all quarantined nodes"
          >
            <RotateCcw size={14} /> RESTORE ALL
          </button>
        </div>
      </header>

      {/* ── Main Canvas Area ── */}
      <main className="flex-1 flex overflow-hidden relative">

        {/* Center Layer: Topology & Traffic Flow */}
        <section className="flex-1 relative border-r border-soc-border bg-[#0a0e17]">
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
            <Controls showInteractive={false} className="!bg-soc-panel !border-soc-border !fill-soc-muted" />
            <MiniMap
              nodeStrokeColor="#374151"
              nodeColor={(n) => {
                const s = n.data?.status;
                if (s === 'QUARANTINED') return '#374151';
                if (s === 'SUSPICIOUS' || s === 'UNDER_ATTACK') return '#ef4444';
                return '#10b981';
              }}
              maskColor="rgba(0,0,0,0.6)"
              style={{ background: '#05070a', border: '1px solid #1f2937' }}
            />
          </ReactFlow>

          {liveSelectedNode && (
            <NodeDetailPanel
              node={liveSelectedNode}
              onClose={() => setSelectedNode(null)}
              packets={packets}
              flows={flows}
              currentRound={currentRound}
            />
          )}

          {/* Attack Injection Overlay */}
          <div className="absolute top-4 left-4 bg-soc-panel/95 backdrop-blur-xl border border-soc-border rounded-xl p-4 w-64 shadow-[0_8px_30px_rgb(0,0,0,0.5)] z-10">
            <h3 className="flex items-center gap-2 text-red-400 font-bold mb-3 text-[11px] uppercase tracking-wider border-b border-soc-border pb-2">
              <Zap size={14} /> Attack Simulator
            </h3>
            <div className="space-y-3">
              <Field label="Target Node">
                <select
                  className="w-full bg-[#05070a] border border-soc-border p-2 rounded-lg text-soc-text outline-none focus:border-red-500 text-xs transition-colors"
                  value={targetNode}
                  onChange={e => setTargetNode(e.target.value)}
                >
                  <option value="">Select target…</option>
                  {nodes.filter(n => n.id !== 'inet-gw').map(n => (
                    <option key={n.id} value={n.id}>{n.data.label} ({n.id})</option>
                  ))}
                </select>
              </Field>

              <Field label="Attack Profile">
                <select
                  className="w-full bg-[#05070a] border border-soc-border p-2 rounded-lg text-soc-text outline-none focus:border-red-500 text-xs transition-colors"
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
                className={`w-full py-2.5 rounded-lg transition-all font-bold text-xs uppercase tracking-wider shadow-lg
                  ${!targetNode
                    ? 'bg-[#05070a] border border-soc-border text-soc-muted cursor-not-allowed'
                    : activeAttacks[targetNode]
                      ? 'bg-amber-900/40 hover:bg-amber-900/60 border border-amber-500 text-amber-400'
                      : 'bg-red-900/30 hover:bg-red-900/50 border border-red-600 text-red-400 hover:scale-[1.02]'
                  }`}
              >
                {activeAttacks[targetNode] ? '⏹ Stop Attack' : '⚡ Launch Attack'}
              </button>
            </div>
          </div>
        </section>

        {/* Right Layer: Event Timeline & Mitigation Logs */}
        <aside className="w-[360px] flex flex-col bg-soc-panel shrink-0 shadow-[-10px_0_30px_rgba(0,0,0,0.3)] z-10 relative">
          
          <div className="flex items-center justify-between p-4 border-b border-soc-border bg-black/20 shrink-0">
            <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-xs tracking-wider">
              <Clock size={14} /> Security Timeline
            </h3>
            <span className="flex items-center gap-1.5 text-[10px]">
              <span className={`w-2 h-2 rounded-full ${simState === 'RUNNING' ? 'bg-emerald-500 animate-pulse' : 'bg-gray-500'}`} />
              {simState === 'RUNNING' ? 'LIVE' : 'OFFLINE'}
            </span>
          </div>

          <div className="flex-1 overflow-y-auto p-4 relative z-10">
            {/* Vertical timeline line */}
            <div className="absolute left-[29px] top-6 bottom-6 w-px bg-soc-border/40 z-0 hidden lg:block" />
            
            <div className="space-y-4 relative z-10">
              {events.map((log, i) => {
                const isFed  = log.type === 'FED_ROUND_COMPLETE';
                const isAtk  = log.type === 'ANOMALY_DETECTED';
                const isMitg = log.type === 'MITIGATION';
                const isTrst = log.type === 'TRUST_CHANGE';
                const isStrt = log.type === 'ATTACK_STARTED';
                
                return (
                  <div key={i} className="flex gap-3">
                    {/* Timeline node */}
                    <div className={`mt-1.5 w-2.5 h-2.5 rounded-full shrink-0 border-2 z-10 shadow-lg
                      ${isFed ? 'bg-cyan-900 border-cyan-400' : 
                        isAtk ? 'bg-red-900 border-red-500' : 
                        isMitg ? 'bg-amber-900 border-amber-400' :
                        isTrst ? 'bg-emerald-900 border-emerald-500' :
                        isStrt ? 'bg-orange-900 border-orange-500' : 'bg-gray-800 border-gray-500'}`} 
                    />
                    
                    {/* Content Card */}
                    <div className={`flex-1 flex flex-col gap-1 text-xs p-3 rounded-xl border backdrop-blur-sm transition-all hover:-translate-y-0.5 shadow-md
                      ${isFed  ? 'bg-cyan-950/10 border-cyan-900/50 hover:border-cyan-500/50'   :
                        isAtk  ? 'bg-red-950/10 border-red-900/50 hover:border-red-500/50'    :
                        isMitg ? 'bg-amber-950/10 border-amber-900/50 hover:border-amber-500/50' :
                        isTrst ? 'bg-emerald-950/5 border-emerald-900/40 hover:border-emerald-500/50' :
                        isStrt ? 'bg-orange-950/10 border-orange-900/50 hover:border-orange-500/50' :
                                 'bg-soc-bg border-soc-border hover:bg-white/5'
                      }`}
                    >
                      <div className="flex justify-between items-start gap-2">
                        <span className={`font-bold text-[10px] uppercase tracking-wider
                          ${isFed ? 'text-cyan-400' : 
                            isAtk ? 'text-red-400' : 
                            isMitg ? 'text-amber-400' :
                            isTrst ? 'text-emerald-400' :
                            isStrt ? 'text-orange-400' : 'text-soc-muted'}`}>
                          {log.type.replace(/_/g, ' ')}
                        </span>
                        <span className="text-soc-muted shrink-0 text-[9px] font-mono opacity-70">
                          {log.timestamp}
                        </span>
                      </div>
                      
                      <div className="text-soc-text text-[11px] leading-snug mt-0.5 opacity-90">
                        {log.message}
                      </div>
                      
                      {log.node && (
                         <div className="mt-1 flex items-center gap-1.5 text-[10px] font-mono text-soc-muted bg-black/20 self-start px-2 py-0.5 rounded">
                           Entity: <span className="text-soc-text font-bold">{log.node}</span>
                         </div>
                      )}
                      
                      {/* XAI Evidence Panel */}
                      {log.explanation && (
                        <div className="mt-2 bg-[#05070a] border border-soc-border/50 rounded-lg p-2">
                          <div className="text-[9px] text-soc-muted uppercase tracking-wider mb-1.5 flex items-center gap-1">
                            <Activity size={10}/> ML Feature Evidence:
                          </div>
                          <div className="flex flex-col gap-1">
                            {Object.entries(log.explanation).slice(0, 3).map(([k, v]) => (
                              <div key={k} className="flex items-center justify-between text-[10px] font-mono">
                                <span className="text-soc-muted">{k}</span>
                                <span className="text-red-400 font-bold">+{(v as number).toFixed(2)}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
              {events.length === 0 && (
                <div className="text-soc-muted text-xs italic flex flex-col items-center justify-center gap-3 pt-12 opacity-50">
                   <Clock size={32} className="opacity-20" />
                   <span>Monitoring network events...</span>
                </div>
              )}
            </div>
          </div>
        </aside>
      </main>

      {/* ── Bottom Layer: Live Traffic Feed & Aggregated Metrics ── */}
      <footer className="h-44 border-t border-soc-border bg-soc-panel shrink-0 flex flex-col shadow-[0_-5px_20px_rgba(0,0,0,0.3)] relative z-20">
        <div className="flex items-center justify-between px-6 py-2.5 border-b border-soc-border bg-black/20">
          <h3 className="flex items-center gap-2 text-soc-muted font-bold uppercase text-[11px] tracking-wider">
            <Database size={13} /> Deep Packet Inspection Feed
          </h3>
          <div className="flex items-center gap-6">
             <div className="flex items-center gap-4 text-[10px] text-soc-muted">
               <span className="flex items-center gap-1.5"><span className="w-2 h-2 rounded-full bg-cyan-400 shadow-[0_0_5px_rgba(34,211,238,0.5)]"></span> Normal Flow</span>
               <span className="flex items-center gap-1.5"><span className="w-2 h-2 rounded-full bg-red-500 shadow-[0_0_5px_rgba(239,68,68,0.5)]"></span> Anomalous</span>
             </div>
             <button
                onClick={() => window.open(`${API_URL}/api/flows/export`, '_blank')}
                className="text-[10px] px-3 py-1 rounded bg-soc-bg border border-soc-border hover:text-cyan-400 hover:border-cyan-400 transition-colors text-soc-muted uppercase tracking-wider font-bold"
              >
                Export Flow Data CSV
              </button>
          </div>
        </div>
        
        <div className="flex-1 flex overflow-hidden">
          {/* Left: Quick KPIs */}
          <div className="w-64 border-r border-soc-border p-3 grid grid-cols-2 gap-2 bg-[#05070a]">
              <KpiCard label="Anomalies" value={anomalyCount} valueClass={anomalyCount > 0 ? 'text-red-400' : 'text-soc-muted'} />
              <KpiCard label="Active Nodes" value={`${activeCount}/${nodes.length}`} valueClass="text-emerald-400" />
              <KpiCard label="Quarantined" value={quarantinedCount} valueClass={quarantinedCount > 0 ? 'text-red-400' : 'text-soc-muted'} />
              <KpiCard label="Net Load" value={`${networkLoad.toFixed(1)} KB/s`} />
          </div>

          {/* Right: Table */}
          <div className="flex-1 overflow-auto bg-soc-bg/50">
            <table className="w-full text-left text-xs">
              <thead className="bg-black/60 text-soc-muted sticky top-0 backdrop-blur-md z-10 shadow-sm text-[10px] uppercase tracking-wider">
                <tr>
                  <th className="px-6 py-2 font-bold">Timestamp</th>
                  <th className="px-4 py-2 font-bold">Source Node</th>
                  <th className="px-4 py-2 font-bold">Dest Node</th>
                  <th className="px-4 py-2 font-bold">Protocol</th>
                  <th className="px-4 py-2 font-bold text-right">Payload</th>
                  <th className="px-6 py-2 font-bold">Local ML Classification</th>
                </tr>
              </thead>
              <tbody>
                {packets.slice(0, 50).map((p, i) => {
                  const isAnomaly = p.label === 'ANOMALY' || p.label === 'ATTACK';
                  return (
                    <tr key={i} className={`border-b border-soc-border/20 hover:bg-white/5 transition-colors ${isAnomaly ? 'bg-red-950/10' : ''}`}>
                      <td className="px-6 py-1.5 text-soc-muted font-mono text-[10px]">{new Date(p.receivedAt ?? Date.now()).toISOString().split('T')[1].slice(0,-1)}</td>
                      <td className="px-4 py-1.5 font-mono">{p.source}</td>
                      <td className="px-4 py-1.5 font-mono">{p.dest}</td>
                      <td className="px-4 py-1.5 text-cyan-400 font-mono text-[10px] font-bold">{p.protocol}</td>
                      <td className="px-4 py-1.5 text-right font-mono text-soc-muted">{p.bytes} B</td>
                      <td className={`px-6 py-1.5 font-bold text-[10px] uppercase tracking-wider ${isAnomaly ? 'text-red-400' : 'text-emerald-400/80'}`}>
                        {p.label === 'ATTACK' ? 'ANOMALY' : p.label}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
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
      className={`px-2.5 py-1 rounded text-xs font-bold transition-all disabled:opacity-30
        ${active ? activeClass : 'text-soc-muted hover:text-soc-text hover:bg-white/5'}`}
    >
      {label}
    </button>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-[10px] text-soc-muted uppercase tracking-wider font-bold">{label}</label>
      {children}
    </div>
  );
}

function KpiCard({ label, value, valueClass = 'text-soc-text' }: { label: string; value: any; valueClass?: string }) {
  return (
    <div className="bg-soc-panel/30 border border-soc-border/50 rounded p-2 flex flex-col justify-center">
      <div className="text-[9px] text-soc-muted uppercase tracking-wider mb-0.5">{label}</div>
      <div className={`text-sm font-bold tabular-nums leading-none ${valueClass}`}>{value}</div>
    </div>
  );
}
