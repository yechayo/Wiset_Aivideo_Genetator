import { useRef, useEffect, useCallback, useState } from 'react';
import { useFusionStore } from '../../../../stores/fusionStore';
import type { PanelOverlay } from '../utils/canvasSplitter';

interface GridCanvasProps {
  sceneGridImage: HTMLImageElement | null;
  characterImages: Map<string, HTMLImageElement>;
  gridColumns: number;
  gridRows: number;
  panelWidth: number;
  panelHeight: number;
  separatorPixels: number;
}

const GridCanvas = ({
  sceneGridImage,
  characterImages,
  gridColumns,
  gridRows,
  panelWidth,
  panelHeight,
  separatorPixels,
}: GridCanvasProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const dragStartRef = useRef({ x: 0, y: 0 });
  const overlayStartRef = useRef({ x: 0, y: 0 });

  const totalWidth = gridColumns * panelWidth + (gridColumns - 1) * separatorPixels;
  const totalHeight = gridRows * panelHeight + (gridRows - 1) * separatorPixels;

  // Display scale factor (fit canvas to container)
  const displayScale = Math.min(1, 800 / totalWidth);

  const render = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    canvas.width = totalWidth;
    canvas.height = totalHeight;

    // Draw full grid image
    if (sceneGridImage) {
      ctx.drawImage(sceneGridImage, 0, 0);
    }

    // Draw overlays
    const state = useFusionStore.getState();
    state.panelOverlays.forEach((overlay: PanelOverlay, panelIndex: number) => {
      const row = Math.floor(panelIndex / gridColumns);
      const col = panelIndex % gridColumns;
      const px = col * (panelWidth + separatorPixels);
      const py = row * (panelHeight + separatorPixels);

      ctx.save();
      ctx.beginPath();
      ctx.rect(px, py, panelWidth, panelHeight);
      ctx.clip();
      ctx.globalAlpha = overlay.opacity;

      const charImage = characterImages.get(overlay.imageUrl);
      if (charImage) {
        const drawWidth = panelWidth * overlay.scale;
        const drawHeight = panelHeight * overlay.scale;
        const drawX = px + overlay.x + (panelWidth - drawWidth) / 2;
        const drawY = py + overlay.y + (panelHeight - drawHeight) / 2;
        ctx.drawImage(charImage, drawX, drawY, drawWidth, drawHeight);
      }

      ctx.restore();
    });

    // Draw selection highlight
    const { selectedPanelIndex } = state;
    if (selectedPanelIndex !== null) {
      const row = Math.floor(selectedPanelIndex / gridColumns);
      const col = selectedPanelIndex % gridColumns;
      const px = col * (panelWidth + separatorPixels);
      const py = row * (panelHeight + separatorPixels);
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 3;
      ctx.strokeRect(px + 1.5, py + 1.5, panelWidth - 3, panelHeight - 3);
    }

    // Draw panel grid lines
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)';
    ctx.lineWidth = 1;
    for (let col = 1; col < gridColumns; col++) {
      const x = col * (panelWidth + separatorPixels) - separatorPixels / 2;
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, totalHeight);
      ctx.stroke();
    }
    for (let row = 1; row < gridRows; row++) {
      const y = row * (panelHeight + separatorPixels) - separatorPixels / 2;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(totalWidth, y);
      ctx.stroke();
    }
  }, [sceneGridImage, characterImages, gridColumns, gridRows, panelWidth, panelHeight, separatorPixels, totalWidth, totalHeight]);

  // Re-render on store changes
  useEffect(() => {
    const unsub = useFusionStore.subscribe(render);
    render();
    return unsub;
  }, [render]);

  const getCanvasCoords = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    return {
      x: (e.clientX - rect.left) * scaleX,
      y: (e.clientY - rect.top) * scaleY,
    };
  }, []);

  const getPanelAtCoord = useCallback((x: number, y: number): number | null => {
    for (let row = 0; row < gridRows; row++) {
      for (let col = 0; col < gridColumns; col++) {
        const px = col * (panelWidth + separatorPixels);
        const py = row * (panelHeight + separatorPixels);
        if (x >= px && x < px + panelWidth && y >= py && y < py + panelHeight) {
          return row * gridColumns + col;
        }
      }
    }
    return null;
  }, [gridColumns, gridRows, panelWidth, panelHeight, separatorPixels]);

  const handleClick = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    if (isDragging) return;
    const { x, y } = getCanvasCoords(e);
    const panelIndex = getPanelAtCoord(x, y);
    useFusionStore.getState().selectPanel(panelIndex);
  }, [getCanvasCoords, getPanelAtCoord, isDragging]);

  const handleMouseDown = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const { selectedPanelIndex, panelOverlays } = useFusionStore.getState();
    if (selectedPanelIndex === null || !panelOverlays.has(selectedPanelIndex)) return;

    const { x, y } = getCanvasCoords(e);
    const panelIndex = getPanelAtCoord(x, y);
    if (panelIndex !== selectedPanelIndex) return;

    setIsDragging(true);
    dragStartRef.current = { x, y };
    const overlay = panelOverlays.get(selectedPanelIndex)!;
    overlayStartRef.current = { x: overlay.x, y: overlay.y };
  }, [getCanvasCoords, getPanelAtCoord]);

  const handleMouseMove = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDragging) return;
    const { selectedPanelIndex } = useFusionStore.getState();
    if (selectedPanelIndex === null) return;

    const { x, y } = getCanvasCoords(e);
    const dx = x - dragStartRef.current.x;
    const dy = y - dragStartRef.current.y;

    useFusionStore.getState().updateOverlayProperty(selectedPanelIndex, {
      x: overlayStartRef.current.x + dx,
      y: overlayStartRef.current.y + dy,
    });
  }, [isDragging, getCanvasCoords]);

  const handleMouseUp = useCallback(() => {
    setIsDragging(false);
  }, []);

  return (
    <canvas
      ref={canvasRef}
      style={{
        width: totalWidth * displayScale,
        height: totalHeight * displayScale,
        cursor: isDragging ? 'grabbing' : 'pointer',
        borderRadius: 12,
        border: '1px solid rgba(255, 255, 255, 0.1)',
      }}
      onClick={handleClick}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
    />
  );
};

export default GridCanvas;
