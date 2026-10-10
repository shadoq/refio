import { Segmented, Select } from "antd";
import { palettes, type ThemeId } from "@/theme/palettes";
import { useT } from "@/i18n/LanguageProvider";

interface ThemeSwitcherProps {
  value: ThemeId;
  onChange: (themeId: ThemeId) => void;
}

const options = Object.values(palettes).map((palette) => ({
  label: palette.label,
  value: palette.id,
}));

export function ThemeSwitcher({ value, onChange }: ThemeSwitcherProps) {
  const t = useT();
  return (
    <div className="theme-switcher" aria-label={t("layout.themeSwitcher")}>
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
        aria-label={t("layout.theme")}
      />
    </div>
  );
}
