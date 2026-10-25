/** Native dialogs can yield Tab to browser chrome when only one control exists. */
export function trapDialogTab(event: KeyboardEvent, dialog: HTMLDialogElement): void {
  if (event.key !== "Tab" || event.ctrlKey || event.altKey || event.metaKey) return;
  const controls = Array.from(
    dialog.querySelectorAll<HTMLElement>("a[href], button, input, select, textarea, [tabindex]"),
  )
    .filter((control) => {
      if (
        control.tabIndex < 0 ||
        control.matches(":disabled") ||
        control.closest("[hidden], [inert], [aria-hidden='true']")
      )
        return false;
      const style = getComputedStyle(control);
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        control.getClientRects().length > 0
      );
    })
    .sort(
      (left, right) =>
        (left.tabIndex || Number.POSITIVE_INFINITY) - (right.tabIndex || Number.POSITIVE_INFINITY),
    );

  const first = controls[0];
  const last = controls[controls.length - 1];
  const active = document.activeElement;
  if (!first) {
    event.preventDefault();
    dialog.focus();
  } else if (!controls.includes(active as HTMLElement)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (event.shiftKey && active === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}
