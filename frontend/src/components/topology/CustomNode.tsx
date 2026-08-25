import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { Server, Monitor, HardDrive, Cpu, Router, Shield } from 'lucide-react';

const icons: Record<string, any> = {
  server: Server,
  pc: Monitor,
  database: HardDrive,
  iot: Cpu,
  router: Router,
  firewall: Shield,
  iot_device: Cpu,
  default: Server,
};

const statusConfig: Record<string, { border: string; iconBg: string; iconColor: string; shadow: string; pulse: boolean; dim: boolean }> = {
  HEALTHY:        { border: 'border-emerald-500/60', iconBg: 'bg-emerald-900/30',  iconColor: 'text-emerald-400', shadow: '',                                        pulse: false, dim: false },
  TRAINING:       { border: 'border-purple-500',     iconBg: 'bg-purple-900/40',   iconColor: 'text-purple-400',  shadow: 'shadow-[0_0_15px_rgba(168,85,247,0.5)]',  pulse: true,  dim: false },
  SYNCHRONIZING:  { border: 'border-blue-400',       iconBg: 'bg-blue-900/30',     iconColor: 'text-blue-400',    shadow: 'shadow-[0_0_12px_rgba(96,165,250,0.5)]',   pulse: true,  dim: false },
  SUSPICIOUS:     { border: 'border-amber-400',      iconBg: 'bg-amber-900/30',    iconColor: 'text-amber-400',   shadow: 'shadow-[0_0_12px_rgba(251,191,36,0.4)]',   pulse: false, dim: false },
  UNDER_ATTACK:   { border: 'border-red-500',        iconBg: 'bg-red-900/30',      iconColor: 'text-red-400',     shadow: 'shadow-[0_0_20px_rgba(239,68,68,0.7)]',    pulse: true,  dim: false },
  QUARANTINED:    { border: 'border-gray-600',       iconBg: 'bg-gray-800',        iconColor: 'text-gray-500',    shadow: '',                                         pulse: false, dim: true  },
};

function CustomNode({ data, selected }: any) {
  const typeKey = (data.type || 'default').toLowerCase().replace('_device', '');
  const Icon = icons[typeKey] || icons.default;
  const status = data.status || 'HEALTHY';
  const cfg = statusConfig[status] || statusConfig.HEALTHY;
  
  const trustScore = data.trustScore ?? 100;
  const trustColor = trustScore < 30 ? 'bg-red-500' : trustScore < 70 ? 'bg-amber-400' : 'bg-emerald-500';

  return (
    <div
      className={`
        relative px-3 py-2.5 rounded-xl border-2 backdrop-blur-md bg-soc-panel/90
        transition-all duration-500
        ${selected ? 'border-cyan-400 shadow-[0_0_18px_rgba(34,211,238,0.6)]' : cfg.border}
        ${selected ? '' : cfg.shadow}
        ${cfg.dim ? 'opacity-40 grayscale' : ''}
        ${cfg.pulse ? 'animate-pulse' : ''}
        min-w-[130px]
      `}
    >
      <Handle type="target" position={Position.Top} className="!w-2 !h-2 !bg-cyan-400 !border-0 opacity-70" />

      <div className="flex items-center gap-2">
        <div className={`p-1.5 rounded-md shrink-0 ${cfg.iconBg} ${cfg.iconColor}`}>
          <Icon size={16} />
        </div>
        <div className="min-w-0">
          <div className="text-[11px] font-bold uppercase tracking-wide text-soc-text truncate">
            {data.label}
          </div>
          <div className={`text-[9px] font-semibold tracking-wider ${cfg.iconColor}`}>
            {status}
          </div>
        </div>
      </div>

      {/* Trust Score bar */}
      <div className="mt-2 w-full bg-black/40 rounded-full h-1 overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-700 ${trustColor}`}
          style={{ width: `${Math.max(0, Math.min(100, trustScore))}%` }}
        />
      </div>
      <div className="text-[9px] text-soc-muted text-right mt-0.5">{trustScore.toFixed(0)}%</div>

      <Handle type="source" position={Position.Bottom} className="!w-2 !h-2 !bg-cyan-400 !border-0 opacity-70" />
    </div>
  );
}

export default memo(CustomNode);
