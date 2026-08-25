import { BaseEdge, getBezierPath } from '@xyflow/react';

export default function AnimatedEdge({
  sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition,
  markerEnd, data,
}: any) {
  const [edgePath] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });

  const isAnomaly = data?.isAnomaly;
  const dotColor  = isAnomaly ? '#ef4444' : '#38bdf8';
  const lineColor = isAnomaly ? 'rgba(239,68,68,0.5)' : 'rgba(56,189,248,0.25)';
  const dotSize   = isAnomaly ? 7 : 4;
  const speed     = isAnomaly ? '0.8s' : '2s';

  return (
    <>
      <BaseEdge
        path={edgePath}
        markerEnd={markerEnd}
        style={{ strokeWidth: isAnomaly ? 2.5 : 1.5, stroke: lineColor, strokeDasharray: isAnomaly ? '6 3' : undefined }}
      />

      {/* Glowing animated packet dot */}
      <circle
        r={dotSize}
        fill={dotColor}
        style={{ filter: `drop-shadow(0 0 ${dotSize + 2}px ${dotColor})` }}
      >
        <animateMotion dur={speed} repeatCount="indefinite" path={edgePath} />
      </circle>
    </>
  );
}
