// All dictionaries, one namespace per area of the app. English is the source: its
// keys define MessageKey, and the Polish set must carry exactly the same keys.
import type { Lang, Message, Translation } from "./core";
import { common as en_common } from "./locales/en/common";
import { layout as en_layout } from "./locales/en/layout";
import { landing as en_landing } from "./locales/en/landing";
import { leaderboard as en_leaderboard } from "./locales/en/leaderboard";
import { help as en_help } from "./locales/en/help";
import { overview as en_overview } from "./locales/en/overview";
import { results as en_results } from "./locales/en/results";
import { taskDetail as en_taskDetail } from "./locales/en/taskDetail";
import { queue as en_queue } from "./locales/en/queue";
import { compare as en_compare } from "./locales/en/compare";
import { stability as en_stability } from "./locales/en/stability";
import { pareto as en_pareto } from "./locales/en/pareto";
import { agents as en_agents } from "./locales/en/agents";
import { charts as en_charts } from "./locales/en/charts";
import { resultView as en_resultView } from "./locales/en/resultView";
import { admin as en_admin } from "./locales/en/admin";
import { common as pl_common } from "./locales/pl/common";
import { layout as pl_layout } from "./locales/pl/layout";
import { landing as pl_landing } from "./locales/pl/landing";
import { leaderboard as pl_leaderboard } from "./locales/pl/leaderboard";
import { help as pl_help } from "./locales/pl/help";
import { overview as pl_overview } from "./locales/pl/overview";
import { results as pl_results } from "./locales/pl/results";
import { taskDetail as pl_taskDetail } from "./locales/pl/taskDetail";
import { queue as pl_queue } from "./locales/pl/queue";
import { compare as pl_compare } from "./locales/pl/compare";
import { stability as pl_stability } from "./locales/pl/stability";
import { pareto as pl_pareto } from "./locales/pl/pareto";
import { agents as pl_agents } from "./locales/pl/agents";
import { charts as pl_charts } from "./locales/pl/charts";
import { resultView as pl_resultView } from "./locales/pl/resultView";
import { admin as pl_admin } from "./locales/pl/admin";

export const en = {
  common: en_common,
  layout: en_layout,
  landing: en_landing,
  leaderboard: en_leaderboard,
  help: en_help,
  overview: en_overview,
  results: en_results,
  taskDetail: en_taskDetail,
  queue: en_queue,
  compare: en_compare,
  stability: en_stability,
  pareto: en_pareto,
  agents: en_agents,
  charts: en_charts,
  resultView: en_resultView,
  admin: en_admin,
};

type Dictionary = typeof en;

export const pl: { [N in keyof Dictionary]: Translation<Dictionary[N]> } = {
  common: pl_common,
  layout: pl_layout,
  landing: pl_landing,
  leaderboard: pl_leaderboard,
  help: pl_help,
  overview: pl_overview,
  results: pl_results,
  taskDetail: pl_taskDetail,
  queue: pl_queue,
  compare: pl_compare,
  stability: pl_stability,
  pareto: pl_pareto,
  agents: pl_agents,
  charts: pl_charts,
  resultView: pl_resultView,
  admin: pl_admin,
};

// "landing.bestRefio", "help.title", ... - every key a t() call may use.
export type MessageKey = {
  [N in keyof Dictionary]: `${N & string}.${keyof Dictionary[N] & string}`;
}[keyof Dictionary];

const dictionaries: Record<Lang, Record<string, Record<string, Message>>> = { en, pl };

export function lookup(lang: Lang, key: MessageKey | string): Message {
  const dot = key.indexOf(".");
  const ns = key.slice(0, dot);
  const name = key.slice(dot + 1);
  return dictionaries[lang][ns]?.[name] ?? dictionaries.en[ns]?.[name] ?? key;
}
