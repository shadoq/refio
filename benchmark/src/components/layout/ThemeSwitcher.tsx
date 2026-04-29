import { Segmented, Select } from "antd";
import { palettes, type ThemeId } from "@/theme/palettes";

interface ThemeSwitcherProps {
  value: ThemeId;
  onChange: (themeId: ThemeId) => void;
}

const options = Object.values(palettes).map((palette) => ({
  label: palette.label,
  value: palette.id,
}));

export function ThemeSwitcher({ value, onChange }: ThemeSwitcherProps) {
  return (
    <div className="theme-switcher" aria-label="Theme switcher">
      <Segmented
        className="theme-switcher-full"
        size="small"
        options={options}
        value={value}
        onChange={(next) => onChange(next as ThemeId)}
      />
      <Select
        className="theme-switcher-compact"
        size="small"
        options={options}
        value={value}
        onChange={(next) => onChange(next as ThemeId)}
        popupMatchSelectWidth={false}
        aria-label="Theme"
      />
    </div>
  );
}
