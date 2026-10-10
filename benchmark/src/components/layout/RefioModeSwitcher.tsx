import { Segmented, Tooltip } from "antd";
import type { RefioMode } from "@/lib/stats";
import { useRefioMode } from "@/store/refioMode";
import { useT } from "@/i18n/LanguageProvider";

export function RefioModeSwitcher() {
  const { mode, setMode } = useRefioMode();
  const t = useT();
  return (
    <Tooltip title={t("leaderboard.vsLeaderHint")} placement="bottom">
      <Segmented
        className="header-segmented"
        size="small"
        aria-label={t("leaderboard.refioLabel")}
        value={mode}
        onChange={(next) => setMode(next as RefioMode)}
        options={[
          { label: t("leaderboard.absolute"), value: "absolute" },
          { label: t("leaderboard.vsLeader"), value: "relative" },
        ]}
      />
    </Tooltip>
  );
}
