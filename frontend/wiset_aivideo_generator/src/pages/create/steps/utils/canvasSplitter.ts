/**
 * Canvas 拆分和合成工具
 */

/**
 * 将九宫格图拆分为单格面板
 */
export function splitGridToPanels(
  gridImage: HTMLImageElement,
  gridColumns: number,
  gridRows: number,
  panelWidth: number,
  panelHeight: number,
  separatorPixels: number,
): Map<number, HTMLCanvasElement> {
  const panels = new Map<number, HTMLCanvasElement>();

  for (let row = 0; row < gridRows; row++) {
    for (let col = 0; col < gridColumns; col++) {
      const panelIndex = row * gridColumns + col;
      const sx = col * (panelWidth + separatorPixels);
      const sy = row * (panelHeight + separatorPixels);

      const canvas = document.createElement('canvas');
      canvas.width = panelWidth;
      canvas.height = panelHeight;
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(gridImage, sx, sy, panelWidth, panelHeight, 0, 0, panelWidth, panelHeight);

      panels.set(panelIndex, canvas);
    }
  }

  return panels;
}

/**
 * 将所有面板合成为完整的网格图（用于导出上传）
 */
export function compositeFusedImage(
  panelCanvases: Map<number, HTMLCanvasElement>,
  gridColumns: number,
  gridRows: number,
  panelWidth: number,
  panelHeight: number,
  separatorPixels: number,
): HTMLCanvasElement {
  const totalWidth = gridColumns * panelWidth + (gridColumns - 1) * separatorPixels;
  const totalHeight = gridRows * panelHeight + (gridRows - 1) * separatorPixels;

  const canvas = document.createElement('canvas');
  canvas.width = totalWidth;
  canvas.height = totalHeight;
  const ctx = canvas.getContext('2d')!;

  ctx.fillStyle = '#000000';
  ctx.fillRect(0, 0, totalWidth, totalHeight);

  for (let row = 0; row < gridRows; row++) {
    for (let col = 0; col < gridColumns; col++) {
      const panelIndex = row * gridColumns + col;
      const panelCanvas = panelCanvases.get(panelIndex);
      if (!panelCanvas) continue;

      const dx = col * (panelWidth + separatorPixels);
      const dy = row * (panelHeight + separatorPixels);
      ctx.drawImage(panelCanvas, dx, dy);
    }
  }

  return canvas;
}

/** Canvas 转 Blob */
export function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob);
        else reject(new Error('Canvas toBlob 失败'));
      },
      'image/png',
      1.0,
    );
  });
}

/** Canvas 转 File（用于上传） */
export async function canvasToFile(canvas: HTMLCanvasElement, filename = 'fusion.png'): Promise<File> {
  const blob = await canvasToBlob(canvas);
  return new File([blob], filename, { type: 'image/png' });
}

/**
 * 渲染带覆盖图的面板
 */
export interface PanelOverlay {
  characterName: string;
  imageUrl: string;
  x: number;
  y: number;
  width: number;
  height: number;
  opacity: number;
  scale: number;
}

export function renderPanelWithOverlay(
  panelCanvas: HTMLCanvasElement,
  overlay: PanelOverlay | undefined,
  overlayImage: HTMLImageElement | undefined,
): HTMLCanvasElement {
  const canvas = document.createElement('canvas');
  canvas.width = panelCanvas.width;
  canvas.height = panelCanvas.height;
  const ctx = canvas.getContext('2d')!;

  ctx.drawImage(panelCanvas, 0, 0);

  if (overlay && overlayImage) {
    ctx.globalAlpha = overlay.opacity;
    const drawWidth = overlay.width * overlay.scale;
    const drawHeight = overlay.height * overlay.scale;
    const drawX = overlay.x + (overlay.width - drawWidth) / 2;
    const drawY = overlay.y + (overlay.height - drawHeight) / 2;
    ctx.drawImage(overlayImage, drawX, drawY, drawWidth, drawHeight);
    ctx.globalAlpha = 1.0;
  }

  return canvas;
}
