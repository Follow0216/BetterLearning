import type { CSSProperties } from "react";

export const BL_ICON_NAMES = [
  "back",
  "chevron_right",
  "inbox",
  "list",
  "logout",
  "refresh",
  "user",
  "warning",
] as const;

export type BlIconName = (typeof BL_ICON_NAMES)[number];

interface BlIconProps {
  name: BlIconName;
  size?: number;
  className?: string;
  label?: string;
}

export function BlIcon({ name, size = 20, className, label }: BlIconProps) {
  const style = {
    "--bl-icon-url": `url("/icons/bl_${name}.svg")`,
    width: `${size}px`,
    height: `${size}px`,
  } as CSSProperties;

  return (
    <span
      className={className === undefined ? "bl-icon" : `bl-icon ${className}`}
      style={style}
      role={label === undefined ? undefined : "img"}
      aria-label={label}
      aria-hidden={label === undefined ? true : undefined}
    />
  );
}