/** Keeps the opening Markdown view's scroll/focus alive across a native citation dialog. */
export class CitationFocusController {
  private citationId: string = '';
  private handler: ((citationId: string) => void) | null = null;
  attach(citationId: string, handler: (citationId: string) => void): void {
    this.citationId = citationId; this.handler = handler;
  }
  detach(handler: (citationId: string) => void): void {
    if (this.handler === handler) { this.clear(); }
  }
  clear(): void { this.citationId = ''; this.handler = null; }
  restore(citationId: string): boolean {
    const handler: ((citationId: string) => void) | null = this.handler;
    if (handler === null || citationId !== this.citationId) { return false; }
    this.clear(); handler(citationId); return true;
  }
}
export const citationFocusController: CitationFocusController = new CitationFocusController();
