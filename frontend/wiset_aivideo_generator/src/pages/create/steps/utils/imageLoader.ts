/**
 * 图片加载工具，处理 crossOrigin
 */

export function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => resolve(img);
    img.onerror = () => {
      // CORS fallback: 不设 crossOrigin 再试一次
      if (img.crossOrigin) {
        const retryImg = new Image();
        retryImg.crossOrigin = null as any;
        retryImg.onload = () => resolve(retryImg);
        retryImg.onerror = () => reject(new Error(`加载图片失败: ${src}`));
        retryImg.src = src;
      } else {
        reject(new Error(`加载图片失败: ${src}`));
      }
    };

    img.src = src;
  });
}

/** 预加载多张图片 */
export async function loadImages(urls: string[]): Promise<Map<string, HTMLImageElement>> {
  const result = new Map<string, HTMLImageElement>();
  await Promise.all(
    urls.filter(Boolean).map(async (url) => {
      try {
        const img = await loadImage(url);
        result.set(url, img);
      } catch (e) {
        console.warn(`预加载图片失败: ${url}`, e);
      }
    })
  );
  return result;
}
