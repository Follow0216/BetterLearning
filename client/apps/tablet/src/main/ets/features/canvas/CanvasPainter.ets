import { CanvasPenKind, CanvasRect, CanvasStroke, canvas_point_width } from 'common';

export function stroke_bounds(stroke: CanvasStroke): CanvasRect {
  let x: number = stroke.points[0].x; let y: number = stroke.points[0].y;
  let right: number = x; let bottom: number = y;
  for (let i: number = 1; i < stroke.points.length; i++) {
    x = Math.min(x, stroke.points[i].x); y = Math.min(y, stroke.points[i].y);
    right = Math.max(right, stroke.points[i].x); bottom = Math.max(bottom, stroke.points[i].y);
  }
  const radius: number = stroke.width / 2;
  return { x: x - radius, y: y - radius, width: right - x + stroke.width, height: bottom - y + stroke.width };
}
export function canvas_intersects(a: CanvasRect, b: CanvasRect): boolean {
  return a.x <= b.x + b.width && b.x <= a.x + a.width && a.y <= b.y + b.height && b.y <= a.y + a.height;
}
function canvas_color(color: string): string {
  if (color.length !== 9) { return color; }
  return 'rgba(' + parseInt(color.substring(3,5),16) + ',' + parseInt(color.substring(5,7),16) + ',' +
    parseInt(color.substring(7,9),16) + ',' + (parseInt(color.substring(1,3),16) / 255).toString() + ')';
}
export function draw_canvas_stroke(context: CanvasRenderer, stroke: CanvasStroke): void {
  const points = stroke.points;
  if (points.length === 0) { return; }
  context.strokeStyle = canvas_color(stroke.color); context.fillStyle = canvas_color(stroke.color);
  context.lineWidth = stroke.width; context.lineCap = 'round'; context.lineJoin = 'round';
  if (stroke.penKind === CanvasPenKind.FOUNTAIN) { draw_pressure_stroke(context,stroke); return; }
  context.beginPath();
  if (points.length === 1) {
    context.arc(points[0].x,points[0].y,stroke.width / 2,0,Math.PI * 2); context.fill(); return;
  }
  context.moveTo(points[0].x,points[0].y);
  for (let i: number = 1; i < points.length; i++) { context.lineTo(points[i].x,points[i].y); }
  context.stroke();
}

/** 圆点与连续四边形放在同一填充 path，接头无缝且半透明颜色只叠加一次。 */
function draw_pressure_stroke(context: CanvasRenderer, stroke: CanvasStroke): void {
  const points = stroke.points;
  context.beginPath();
  for (let i: number = 0; i < points.length; i++) {
    const point = points[i];
    const radius: number = canvas_point_width(stroke,point) / 2;
    context.moveTo(point.x + radius,point.y);
    context.arc(point.x,point.y,radius,0,Math.PI * 2); context.closePath();
    if (i === 0) { continue; }
    const previous = points[i - 1];
    const previousRadius: number = canvas_point_width(stroke,previous) / 2;
    const dx: number = point.x - previous.x; const dy: number = point.y - previous.y;
    const distance: number = Math.hypot(dx,dy);
    if (distance < 0.000001) { continue; }
    const nx: number = -dy / distance; const ny: number = dx / distance;
    context.moveTo(previous.x + nx * previousRadius,previous.y + ny * previousRadius);
    context.lineTo(previous.x - nx * previousRadius,previous.y - ny * previousRadius);
    context.lineTo(point.x - nx * radius,point.y - ny * radius);
    context.lineTo(point.x + nx * radius,point.y + ny * radius); context.closePath();
  }
  context.fill();
}
