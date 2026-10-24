export interface AnswerTileBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface AnswerTileImage extends AnswerTileBounds {
  index: number;
  source: string;
}

export interface AnswerViewTransform {
  scale: number;
  offsetX: number;
  offsetY: number;
}

export interface AnswerTileGeometry {
  bounds: AnswerTileBounds;
  tiles: AnswerTileImage[];
  error: string;
}

function validBounds(bounds: AnswerTileBounds): boolean {
  return (
    Number.isFinite(bounds.x) &&
    Number.isFinite(bounds.y) &&
    Number.isFinite(bounds.width) &&
    Number.isFinite(bounds.height) &&
    bounds.width > 0 &&
    bounds.height > 0 &&
    Number.isFinite(bounds.x + bounds.width) &&
    Number.isFinite(bounds.y + bounds.height)
  );
}

/** Pure display geometry: the caller owns decoding, authentication and the manifest. */
export function calculateAnswerGeometry(
  bounds: AnswerTileBounds,
  tiles: AnswerTileImage[],
): AnswerTileGeometry {
  const invalid: AnswerTileGeometry = {
    bounds: { x: 0, y: 0, width: 1, height: 1 },
    tiles: [],
    error: "作答图像信息不完整，请重新加载。",
  };
  if (!validBounds(bounds)) {
    return invalid;
  }
  let minX: number = bounds.x;
  let minY: number = bounds.y;
  let maxX: number = bounds.x + bounds.width;
  let maxY: number = bounds.y + bounds.height;
  const indexes: Set<number> = new Set<number>();
  for (let index: number = 0; index < tiles.length; index++) {
    const tile: AnswerTileImage = tiles[index];
    if (
      !validBounds(tile) ||
      !Number.isInteger(tile.index) ||
      tile.index < 0 ||
      indexes.has(tile.index) ||
      typeof tile.source !== "string" ||
      tile.source.trim().length === 0
    ) {
      return invalid;
    }
    indexes.add(tile.index);
    minX = Math.min(minX, tile.x);
    minY = Math.min(minY, tile.y);
    maxX = Math.max(maxX, tile.x + tile.width);
    maxY = Math.max(maxY, tile.y + tile.height);
  }
  const combined: AnswerTileBounds = { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
  if (!validBounds(combined)) {
    return invalid;
  }
  const ordered: AnswerTileImage[] = tiles.slice();
  ordered.sort(
    (first: AnswerTileImage, second: AnswerTileImage): number => first.index - second.index,
  );
  return { bounds: combined, tiles: ordered, error: "" };
}

/** Includes the complete bounds even when the first tile is away from the origin. */
export function fitAnswerView(
  bounds: AnswerTileBounds,
  viewportWidth: number,
  viewportHeight: number,
  padding: number = 16,
): AnswerViewTransform {
  if (
    !validBounds(bounds) ||
    !Number.isFinite(viewportWidth) ||
    !Number.isFinite(viewportHeight) ||
    viewportWidth <= 0 ||
    viewportHeight <= 0
  ) {
    return { scale: 1, offsetX: 0, offsetY: 0 };
  }
  const inset: number = Math.min(
    Math.max(Number.isFinite(padding) ? padding : 0, 0),
    Math.min(viewportWidth, viewportHeight) / 4,
  );
  const scale: number = Math.min(
    (viewportWidth - inset * 2) / bounds.width,
    (viewportHeight - inset * 2) / bounds.height,
  );
  return {
    scale: scale,
    offsetX: (viewportWidth - bounds.width * scale) / 2 - bounds.x * scale,
    offsetY: (viewportHeight - bounds.height * scale) / 2 - bounds.y * scale,
  };
}

export function panAnswerView(
  view: AnswerViewTransform,
  deltaX: number,
  deltaY: number,
): AnswerViewTransform {
  return { scale: view.scale, offsetX: view.offsetX + deltaX, offsetY: view.offsetY + deltaY };
}

/** Preserve the world point beneath the supplied viewport anchor. */
export function zoomAnswerViewAt(
  view: AnswerViewTransform,
  nextScale: number,
  anchorX: number,
  anchorY: number,
): AnswerViewTransform {
  if (!Number.isFinite(nextScale) || nextScale <= 0) {
    return view;
  }
  const ratio: number = nextScale / view.scale;
  return {
    scale: nextScale,
    offsetX: anchorX - (anchorX - view.offsetX) * ratio,
    offsetY: anchorY - (anchorY - view.offsetY) * ratio,
  };
}

export function clampAnswerScale(scale: number, fitScale: number): number {
  return Math.max(fitScale / 8, Math.min(Math.max(8, fitScale * 8), scale));
}
