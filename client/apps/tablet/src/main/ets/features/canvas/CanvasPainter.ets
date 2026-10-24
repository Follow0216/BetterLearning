import { CanvasRect, CanvasStroke } from 'common';

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
  context.beginPath();
  if (points.length === 1) {
    context.arc(points[0].x,points[0].y,stroke.width / 2,0,Math.PI * 2); context.fill(); return;
  }
  context.moveTo(points[0].x,points[0].y);
  for (let i: number = 1; i < points.length; i++) { context.lineTo(points[i].x,points[i].y); }
  context.stroke();
}
