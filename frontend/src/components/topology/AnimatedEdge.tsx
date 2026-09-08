import { BaseEdge, getBezierPath } from '@xyflow/react';

export default function AnimatedEdge({
  sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition,
  markerEnd, data,
}: any) {
  const [edgePath] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });

  const isActive = data?.isActive;
  const isAnomaly = data?.isAnomaly;
  const volume = data?.volume || 0; // 0 to 1

  // Base styling for inactive links
  let lineColor = 'rgba(55, 65, 81, 0.4)'; // soc-border
  let strokeWidth = 1.5;
  let strokeDasharray = 'none';
  let animationSpeed = '0s';
  let dropShadow = 'none';

  if (isActive) {
    if (isAnomaly) {
      lineColor = 'rgba(239, 68, 68, 0.8)'; // Red
      strokeWidth = 2 + (volume * 2); // Thicker for high volume
      strokeDasharray = '10 5';
      animationSpeed = '0.5s'; // Very fast
      dropShadow = 'drop-shadow(0 0 4px rgba(239,68,68,0.6))';
    } else {
      lineColor = 'rgba(56, 189, 248, 0.6)'; // Cyan
      strokeWidth = 1.5 + (volume * 1.5);
      strokeDasharray = '15 10';
      animationSpeed = '2s'; // Slower, normal flow
      dropShadow = 'drop-shadow(0 0 2px rgba(56,189,248,0.4))';
    }
  }

  return (
    <>
      <style>
        {`
          .flow-animation {
            animation: dash ${animationSpeed} linear infinite;
          }
          @keyframes dash {
            to {
              stroke-dashoffset: -20;
            }
          }
        `}
      </style>
      <BaseEdge
        path={edgePath}
        markerEnd={markerEnd}
        className={isActive ? 'flow-animation' : ''}
        style={{
          strokeWidth,
          stroke: lineColor,
          strokeDasharray,
          filter: dropShadow,
          transition: 'all 0.3s ease-in-out'
        }}
      />
    </>
  );
}
