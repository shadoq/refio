export type ThemeId =
  | "refio"
  | "jclab"
  | "aurora"
  | "mono"
  | "cyberpunk"
  | "monoGreen"
  | "dracula"
  | "psychedelic"
  | "ocean"
  | "ember"
  | "synthwave"
  | "ice";

export interface BenchmarkPalette {
  id: ThemeId;
  label: string;
  colorPrimary: string;
  colorInfo: string;
  colorSuccess: string;
  colorBgBase: string;
  colorBgContainer: string;
  colorBorder: string;
  colorText: string;
  colorTextSecondary: string;
}

export const palettes: Record<ThemeId, BenchmarkPalette> = {
  refio: {
    id: "refio",
    label: "Neon",
    colorPrimary: "#46f4a6",
    colorInfo: "#49c7ff",
    colorSuccess: "#46f4a6",
    colorBgBase: "#0c1118",
    colorBgContainer: "rgba(20, 30, 44, 0.74)",
    colorBorder: "rgba(120, 150, 180, 0.18)",
    colorText: "#e6edf6",
    colorTextSecondary: "#9fb0c5",
  },
  jclab: {
    id: "jclab",
    label: "Solar",
    colorPrimary: "#ffb000",
    colorInfo: "#2de2e6",
    colorSuccess: "#73fbd3",
    colorBgBase: "#0a0b10",
    colorBgContainer: "rgba(25, 24, 34, 0.78)",
    colorBorder: "rgba(255, 176, 0, 0.2)",
    colorText: "#fff7e5",
    colorTextSecondary: "#d2c7b2",
  },
  aurora: {
    id: "aurora",
    label: "Aurora",
    colorPrimary: "#8cffc1",
    colorInfo: "#7aa7ff",
    colorSuccess: "#8cffc1",
    colorBgBase: "#07131d",
    colorBgContainer: "rgba(13, 34, 50, 0.76)",
    colorBorder: "rgba(140, 255, 193, 0.18)",
    colorText: "#eef9ff",
    colorTextSecondary: "#a9c1ce",
  },
  mono: {
    id: "mono",
    label: "Mono",
    colorPrimary: "#f4f0e8",
    colorInfo: "#a8b3c7",
    colorSuccess: "#e6edf6",
    colorBgBase: "#0d0d0f",
    colorBgContainer: "rgba(28, 29, 33, 0.78)",
    colorBorder: "rgba(244, 240, 232, 0.16)",
    colorText: "#f4f0e8",
    colorTextSecondary: "#a7a7a7",
  },
  cyberpunk: {
    id: "cyberpunk",
    label: "Cyberpunk",
    colorPrimary: "#fcee09",
    colorInfo: "#00f5ff",
    colorSuccess: "#39ff14",
    colorBgBase: "#090016",
    colorBgContainer: "rgba(29, 12, 48, 0.8)",
    colorBorder: "rgba(252, 238, 9, 0.24)",
    colorText: "#fff8b8",
    colorTextSecondary: "#c9b8ff",
  },
  monoGreen: {
    id: "monoGreen",
    label: "Mono Green",
    colorPrimary: "#8cff8c",
    colorInfo: "#4eea7a",
    colorSuccess: "#8cff8c",
    colorBgBase: "#020a05",
    colorBgContainer: "rgba(5, 24, 12, 0.82)",
    colorBorder: "rgba(140, 255, 140, 0.2)",
    colorText: "#d9ffd9",
    colorTextSecondary: "#88b888",
  },
  dracula: {
    id: "dracula",
    label: "Dracula",
    colorPrimary: "#bd93f9",
    colorInfo: "#8be9fd",
    colorSuccess: "#50fa7b",
    colorBgBase: "#282a36",
    colorBgContainer: "rgba(40, 42, 54, 0.82)",
    colorBorder: "rgba(189, 147, 249, 0.24)",
    colorText: "#f8f8f2",
    colorTextSecondary: "#c7c2dc",
  },
  psychedelic: {
    id: "psychedelic",
    label: "Psychedelic",
    colorPrimary: "#ff2bd6",
    colorInfo: "#20f6ff",
    colorSuccess: "#d8ff2f",
    colorBgBase: "#130019",
    colorBgContainer: "rgba(42, 10, 57, 0.8)",
    colorBorder: "rgba(255, 43, 214, 0.25)",
    colorText: "#fff2ff",
    colorTextSecondary: "#f0b9ff",
  },
  ocean: {
    id: "ocean",
    label: "Ocean",
    colorPrimary: "#35d0ff",
    colorInfo: "#5cf2c8",
    colorSuccess: "#5cf2c8",
    colorBgBase: "#04131f",
    colorBgContainer: "rgba(8, 35, 52, 0.82)",
    colorBorder: "rgba(53, 208, 255, 0.22)",
    colorText: "#e8fbff",
    colorTextSecondary: "#9cc7d4",
  },
  ember: {
    id: "ember",
    label: "Ember",
    colorPrimary: "#ff7a1a",
    colorInfo: "#ffd166",
    colorSuccess: "#91d66b",
    colorBgBase: "#120806",
    colorBgContainer: "rgba(42, 18, 12, 0.82)",
    colorBorder: "rgba(255, 122, 26, 0.24)",
    colorText: "#fff0df",
    colorTextSecondary: "#d3a58b",
  },
  synthwave: {
    id: "synthwave",
    label: "Synthwave",
    colorPrimary: "#ff4fd8",
    colorInfo: "#38f8ff",
    colorSuccess: "#faff5a",
    colorBgBase: "#100022",
    colorBgContainer: "rgba(34, 8, 62, 0.82)",
    colorBorder: "rgba(255, 79, 216, 0.25)",
    colorText: "#fff1fb",
    colorTextSecondary: "#d7a8ff",
  },
  ice: {
    id: "ice",
    label: "Ice",
    colorPrimary: "#b9f3ff",
    colorInfo: "#7cb8ff",
    colorSuccess: "#7ee6c8",
    colorBgBase: "#071018",
    colorBgContainer: "rgba(17, 36, 50, 0.82)",
    colorBorder: "rgba(185, 243, 255, 0.22)",
    colorText: "#f3fbff",
    colorTextSecondary: "#a8c0cc",
  },
};

export const defaultThemeId: ThemeId = "jclab";

export function isThemeId(value: string | null): value is ThemeId {
  return (
    value === "refio" ||
    value === "jclab" ||
    value === "aurora" ||
    value === "mono" ||
    value === "cyberpunk" ||
    value === "monoGreen" ||
    value === "dracula" ||
    value === "psychedelic" ||
    value === "ocean" ||
    value === "ember" ||
    value === "synthwave" ||
    value === "ice"
  );
}
