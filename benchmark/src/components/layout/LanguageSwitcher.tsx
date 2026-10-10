import { Segmented } from "antd";
import { useLang, useT } from "@/i18n/LanguageProvider";
import type { Lang } from "@/i18n/core";

export function LanguageSwitcher() {
  const { lang, setLang } = useLang();
  const t = useT();
  return (
    <Segmented
      className="header-segmented"
      size="small"
      aria-label={t("layout.language")}
      value={lang}
      onChange={(next) => setLang(next as Lang)}
      options={[
        { label: "EN", value: "en" },
        { label: "PL", value: "pl" },
      ]}
    />
  );
}
