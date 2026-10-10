import type { Messages } from "../../core";

export const help = {
  title: "Help",
  intro: "Metric definitions used by the benchmark views and individual result pages.",

  metricRefioScoreName: "Refio Score",
  metricRefioScoreDesc:
    "The one number that ranks the leaderboard. Quality is two thirds the human Avg Score and one third the blind Judge Score (human alone when no judge scored the group), because the human score is the owner's own verdict and the judges are a second opinion, then scaled by Avg Stability. Stability multiplies instead of adding, so a model that fails the same way every time earns nothing for being consistent, and the least stable model keeps 80% of its quality. Every judge verdict counts here, including one from the agent that produced the run, because judges score blind. Shows '-' when the group has no stability measurement. The absolute value keeps its meaning over time. The 'vs leader' toggle above the leaderboard instead divides each task's quality by the best result on that task, so a hard task lowers nobody who did as well as possible on it, applies the same stability factor and shows the result as a share of the leader (100%). That view moves whenever a stronger model or a new task is added.",
  metricAvgScoreName: "Avg Score",
  metricAvgScoreDesc:
    "Overall quality score for a result group. Each criterion value is normalized by its maximum scale value, then averaged across criteria and attempts.",
  metricJudgeScoreName: "Judge Score",
  metricJudgeScoreDesc:
    "Overall quality as scored by strong-judge agents (Claude Code, Codex), independent of the human Avg Score. Per result the judges' median value per criterion is weighted-normalized like the human score, then averaged over judged attempts. The count shows judged / all attempts.",
  metricPassRateName: "Pass Rate",
  metricPassRateDesc:
    "Share of attempts where the normalized result score is at least 50% (3 of 6: it works, even if with visible defects). It is a quick success-rate signal.",
  metricFirstShotName: "First-shot",
  metricFirstShotDesc:
    "Normalized score for the first attempt. The OK/Fix tag is based on the works_out_of_box criterion when available, otherwise on the first-shot score.",
  metricReliabilityName: "Reliability",
  metricReliabilityDesc:
    "Stability across attempts. It is clamped to 0-100%. A model with consistent scores gets a higher reliability value.",
  metricAvgStabilityName: "Avg Stability",
  metricAvgStabilityDesc:
    "How consistent a model is across repeated attempts at the same task, averaged over tasks. Unlike Reliability it also checks whether the attempts are built the same way and asks the judges. Shows '-' when the model has no stability groups. See the Stability section below.",
  metricLocalViabilityName: "Local Viability",
  metricLocalViabilityDesc:
    "Local-only metric. localQualityRatio compares the local average score against the best cloud average score. Stability uses Reliability when available, otherwise Pass Rate.",
  metricAvgDurationName: "Avg Duration",
  metricAvgDurationDesc:
    "Average runtime for the filtered attempts in a model and environment group. The admin form accepts seconds, and the UI formats longer values as minutes and seconds.",
  metricLlmEstName: "LLM Est.",
  metricLlmEstDesc:
    "Estimated token-processing time split into prefill and decode. This uses the measured attempt duration because the current benchmark data does not store TTFT/decode telemetry separately.",
  metricTokenSpeedName: "Token Speed",
  metricTokenSpeedDesc:
    "Effective prefill and decode throughput derived from each run's token counts and duration. Treat it as benchmark-effective speed, not raw provider telemetry.",
  metricAvgApiCostName: "Avg API Cost",
  metricAvgApiCostDesc:
    "Average cloud/API cost per attempt for the filtered model and environment group. This is usually the better cost metric for comparing one run against another.",

  fieldTaskName: "Task",
  fieldTaskDesc: "Benchmark scenario being evaluated, for example Snake.",
  fieldModelName: "Model",
  fieldModelDesc: "Model identifier and display name from results.json.",
  fieldEnvironmentName: "Environment",
  fieldEnvironmentDesc:
    "Runtime target, such as local DGX or cloud API. Environment type is local or cloud.",
  fieldAttemptName: "Attempt",
  fieldAttemptDesc: "Attempt number for the same task, model and environment combination.",
  fieldTokensName: "Tokens",
  fieldTokensDesc:
    "Displayed as input / output tokens when token counts are available. Token counts also drive the estimated prefill/decode speed metrics.",
  fieldAttachmentsName: "Attachments",
  fieldAttachmentsDesc:
    "Optional screenshots, HTML previews, videos or embeds attached to a specific benchmark result.",

  normTitle: "Score Normalization",
  normP1a: "Raw criterion values use the scale defined in ",
  normP1b:
    ". Every criterion uses one 0-6 scale: 0 missing, 1 fragment, 2 partial, 3 works with clear defects, 4 good, 5 very good, 6 exceptional. A score is normalized as ",
  normP1c:
    ", so 6 is 100%, 4 is 67% and 3 is 50%. Results scored before the 0-6 scale were rescaled with their top mark at 4 (good), since the old scale could not tell good from exceptional; only the old \"excellent\" look became 6.",
  normP2:
    "Result score is the average of all normalized criterion scores present in that result. Leaderboard rows then aggregate those result scores for each model and environment pair after the active filters are applied.",
  normP3a: "Leaderboard ranking is sorted by ",
  normP3b: ". If two rows have the same Avg Score, ties are broken by average ",
  normP3c: ", then average ",
  normP3d: ", then ",
  normP3e: ".",

  fieldsLabel: "Fields on the Results page",

  agentsLabel: "Agents track: Claude Code, Codex, Gemini CLI, Hermes",
  agentsP1a: "Every result records a ",
  agentsP1Harness: "harness",
  agentsP1b: ": what drove the agent. Almost all of them say ",
  agentsP1c:
    ", our own headless CLI. The agents track is the same tasks run by an external coding agent - Claude Code, Codex or Gemini CLI - on its own model. It has its own page, Agents; the leaderboard, Results, Compare and Pareto all show the Refio track by default.",
  agentsP2:
    "It is kept out of the leaderboard on purpose. The leaderboard answers \"which model should Refio default to\", and an external agent brings its own planning, tools, retries and self-checking, so what it scores is the whole system rather than the model. Mixing the two would make both unreadable. The agents track answers a different question: how far a local model is from what people already have on their desks, and whether a strong model behaves differently when a different agent drives it.",
  agentsP3a: "A model id starting with ",
  agentsP3b:
    " means the agent was pointed at the local Ollama endpoint rather than its own cloud provider, so the same local model can be measured under Refio and under an external agent. Those pairs are what the delta table on the Agents page shows.",
  agentsP4Trace: "The run trace.",
  agentsP4a:
    " Every run now records what the agent actually did, step by step: each assistant turn, each tool call with the file or command it touched, each result. The numbers next to a run - turns, tool calls, reads, writes, shell commands, when the first write happened - are counted from that log by plain arithmetic, never by asking a model. ",
  agentsP4SelfCheck: "Self-check",
  agentsP4b:
    " means the model itself ran a build or a test during the run; a loop that verifies on the model's behalf does not count, so the comparison stays about the model's own discipline. The log never contains file contents: the artifact already has those.",
  agentsP5a: "Three things to keep in mind when reading it. ",
  agentsP5b: " measures the harness in this track, not the model - that is what the track is for. ",
  agentsP5Cost: "Cost",
  agentsP5c:
    " is not comparable: an external agent bills by subscription, so any figure shown is a per-token estimate, never a charged amount, and the Pareto view says so when it is on the chart. And the judges are themselves Claude Code and Codex: on the Agents page a judge's verdict on a run its own agent produced is dropped from the aggregate, because marking your own work is not a measurement. Elsewhere the aggregate is unchanged.",
  agentsP6:
    "The run conditions of each harness - network access, permission mode, version - are recorded on the harness record and shown on the Agents page. How long an agent may work and how many turns it gets scale with the task's difficulty: 15 minutes for an easy task, 30 for a medium one, an hour for a hard one and two hours for a stress task. A cap that bites would measure the cap rather than the agent.",

  judgeLabel: "Strong-judge scoring",
  judgeP1a: "On top of the manual scores, artifacts can be scored by ",
  judgeP1Agents: "strong-judge agents",
  judgeP1b: " - external CLI agents (Claude Code and Codex) run headless and read-only via ",
  judgeP1c:
    ". Each artifact is rendered with Playwright (two screenshots plus captured console errors), and every judge scores it blind: it never sees the human scores or the other judge's scores.",
  judgeP2Criteria: "Criteria.",
  judgeP2a:
    " Judges score the same criteria as the human (Compliance, Works out of the box, Look, Code quality) plus two judge-only criteria: ",
  judgeP2b: " (structure, naming, duplication, dead code) and ",
  judgeP2c: " (correctness read from the code, not only the screen). The human ",
  judgeP2d: " criterion is ",
  judgeP2Not: "not",
  judgeP2e:
    " judged - it rates the coding agent's workflow (check files, edit, verify, summarize), which cannot be seen in a static artifact.",
  judgeP3Title: "Aggregate and divergence.",
  judgeP3a:
    " Per criterion the aggregate is the median across judges, computed in the viewer and never stored. The Results page shows an aggregate ",
  judgeP3AutoColumn: "Auto (judges)",
  judgeP3b: " column with a ",
  judgeP3Badge: "divergence badge",
  judgeP3c:
    " when the human and the judge aggregate differ by at least 2 points on a shared criterion. The Leaderboard, Compare and Pareto pages expose the per-model ",
  judgeP3d: " summary, and Compare adds a dedicated judge radar.",
  judgeP4Title: "Stability.",
  judgeP4a: " Across repeated attempts of one model on a task, stability records deterministic metrics - ",
  judgeP4b:
    " (mean absolute deviation of the judge aggregate between attempts, lower is more stable) and ",
  judgeP4c:
    " (token-Jaccard over the artifacts) - plus a judge verdict over all attempts. It shows on the task page, and the Stability page compares models on it with radars and a ranking.",
  judgeP5Title: "Review.",
  judgeP5:
    " Judge scores are advisory: they never overwrite the manual scores, and a human reads them in the result detail alongside the human ones.",

  stabilityLabel: "Stability page and Avg Stability",
  stabilityP1:
    "Stability answers a different question than quality: if you run the same model on the same task again, do you get the same kind of result? It is computed per group - all attempts of one model on one task in one environment and harness. A group needs at least two attempts with an HTML artifact.",
  stabilityP2: "Each group gets three signals, each on a 0-100% scale where higher means more stable:",
  stabilityScoreTitle: "Score consistency",
  stabilityScoreA: " - how close the judge scores of the attempts are to each other: ",
  stabilityScoreB: ", where ",
  stabilityScoreC:
    " is the mean absolute deviation between attempts on the 0-6 scale. Like Reliability, it treats a deviation of half the scale as unrelated attempts.",
  stabilityCodeTitle: "Code similarity",
  stabilityCode:
    " - token overlap (Jaccard) between the attempts' artifacts. Low values mean the model writes the solution differently every time, even when the scores are close.",
  stabilityVerdictTitle: "Judge verdict",
  stabilityVerdictA: " - each strong judge looks at all attempts at once and answers ",
  stabilityVerdictB: " (same approach, comparable quality), ",
  stabilityVerdictC: " (same approach, variable quality) or ",
  stabilityVerdictD:
    " (different approaches or wildly different quality). The group uses the median across judges.",
  stabilityOverallTitle: "Overall stability",
  stabilityOverallA:
    " of a group is the equal-weight mean of the three signals. A group no judge has scored yet is averaged over the two deterministic signals only, so a missing verdict never counts as 0. The model value (",
  stabilityOverallB: " on the Leaderboard) is the mean over its tasks.",
  stabilityVsTitle: "Avg Stability vs Reliability.",
  stabilityVs:
    " Reliability only looks at how much the scores move between attempts. Avg Stability also asks whether the attempts are built the same way and what the judges think, so the two can differ a lot: a model can score equally badly every time (high Reliability) while producing a different program on each attempt (low Avg Stability), or the other way round.",
  stabilityPageTitle: "The Stability page",
  stabilityPage:
    " shows two radars for up to six selected models - stability per task and per signal (with one axis per judge) - plus the same values as tables, where the best value in each row is highlighted, and a ranking of all models. Clicking a ranking row adds the model to the comparison or removes it. The selection is shared with the Compare page. Global environment, task and harness filters apply; hidden tasks are left out.",

  paretoLabel: "Pareto Explorer",
  paretoP:
    "Pareto charts compare two metrics at once. For quality, reliability, first-shot, pass rate, token speed and local viability, higher is better. For cost, duration and estimated LLM time, lower is better. Points near the better edge on both axes represent stronger trade-offs.",

  radarsLabel: "Compare page radars",
  radarsP1:
    "The Compare page renders three radar charts. Each axis is plotted on a 0-100% scale where higher is always better.",
  radarsCriterionTitle: "Average Score per Criterion",
  radarsCriterionA:
    " uses raw normalized scores per criterion (Compliance, Works out of the box, Look, Code quality, Agent logic). For each model the value on a given axis is the mean of ",
  radarsCriterionB: " across all attempts of that model on that criterion.",
  radarsDerivedTitle: "Derived Benchmark Metrics",
  radarsDerived:
    " aggregates leaderboard fields per model. To keep values stable when models are added or removed from the selection, normalization uses fixed reference points computed from the full leaderboard (all models, after global filters):",
  radarsRatioTitle:
    "Refio Score, Avg Score, Pass Rate, First-shot, Reliability, Local Viability",
  radarsRatio: " - already in the 0-1 range, used as raw values clamped to [0, 1].",
  radarsSpeedTitle: "Input Speed, Output Speed",
  radarsSpeedA:
    " - higher is better. Value is divided by the 95th percentile of all leaderboard rows and clamped to 1: ",
  radarsSpeedB: ". The top tier hits 100%, slower models scale linearly.",
  radarsCostTitle: "Avg Speed (duration), API Cost",
  radarsCostA:
    " - lower is better. Value is mapped against the 5th percentile floor of all leaderboard rows: ",
  radarsCostB:
    ". The fastest/cheapest model hits 100%; a model 2x slower or more expensive sits at 50%, 10x at 10%. Values never collapse to 0 just for being above the median.",
  radarsAxisA:
    "When a model has multiple environment rows in the leaderboard, the per-axis value is averaged across them. An axis is dropped from the chart whenever ",
  radarsAxisAny: "any",
  radarsAxisB:
    " selected model has no data for it - partial coverage would force the missing model to 0% and visually distort the polygon. In practice this means mixing cloud and local models hides API Cost (null for local) and Local Viability (null for cloud), so only directly comparable metrics remain. Selecting different models does not change the position of any other model on the radar.",
  radarsTaskTitle: "Model Behavior by Task",
  radarsTask:
    " averages normalized criterion scores per task per model. Recharts requires at least three axes to draw a polygon, so with only one or two tasks the chart degenerates into a line - use the Per-task Breakdown table below for exact values.",

  tokenLabel: "Token speed calculation",
  tokenP1:
    "LLM inference is split into prefill, where input tokens are processed to build model state, and decode, where output tokens are generated sequentially.",
  tokenP2a: "Current benchmark data stores ",
  tokenP2b: " and ",
  tokenP2c:
    ", but not separate TTFT/prefill/decode timings. Until those are captured, the UI estimates the split from measured run time.",
  tokenP3a: "When both input and output tokens exist: ",
  tokenP3b: " and ",
  tokenP3c: ". Then ",
  tokenP3d: ".",
  tokenP4:
    "If only one token side exists, the full measured duration is assigned to that side. Leaderboard values are averages of these per-attempt estimates for the model and environment group.",
} satisfies Messages;
