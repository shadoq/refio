import { useEffect } from "react";
import { Segmented, Tooltip } from "antd";
import { useFilters } from "@/store/filters";
import { useResults } from "@/data/queries";
import { useT } from "@/i18n/LanguageProvider";

export function LocalOnlySwitcher() {
  const t = useT();
  const { data: resultsData } = useResults();
  const localOnly = useFilters((s) => s.localOnly);
  const setLocalOnly = useFilters((s) => s.setLocalOnly);
  const setLocalEnvironmentIds = useFilters((s) => s.setLocalEnvironmentIds);

  // The filter matches on environment ids, so it needs to know which ones are local.
  useEffect(() => {
    setLocalEnvironmentIds(
      (resultsData?.environments ?? []).filter((e) => e.type === "local").map((e) => e.id),
    );
  }, [resultsData, setLocalEnvironmentIds]);

  return (
    <Tooltip title={t("layout.localHint")} placement="bottom">
      <Segmented
        className="header-segmented"
        size="small"
        aria-label={t("layout.localHint")}
        value={localOnly ? "local" : "all"}
        onChange={(next) => setLocalOnly(next === "local")}
        options={[
          { label: t("layout.localAll"), value: "all" },
          { label: t("layout.localOnly"), value: "local" },
        ]}
      />
    </Tooltip>
  );
}
