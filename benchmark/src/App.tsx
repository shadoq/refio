import { useEffect, useMemo, useState } from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConfigProvider, theme } from "antd";
import { AppShell, DevOnly } from "@/components/layout/AppShell";
import { ErrorBoundary } from "@/components/layout/ErrorBoundary";
import { defaultThemeId, isThemeId, palettes, type ThemeId } from "@/theme/palettes";
import Landing from "@/routes/Landing";
import TaskDetail from "@/routes/TaskDetail";
import Compare from "@/routes/Compare";
import Pareto from "@/routes/Pareto";
import Results from "@/routes/Results";
import Help from "@/routes/Help";
import Queue from "@/routes/Queue";
import ResultEditor from "@/routes/admin/ResultEditor";
import TaskEditor from "@/routes/admin/TaskEditor";
import ModelEditor from "@/routes/admin/ModelEditor";
import EnvironmentEditor from "@/routes/admin/EnvironmentEditor";

const queryClient = new QueryClient();

export default function App() {
  const [themeId, setThemeId] = useState<ThemeId>(() => {
    if (typeof window === "undefined") return defaultThemeId;
    const stored = window.localStorage.getItem("benchmark-theme");
    return isThemeId(stored) ? stored : defaultThemeId;
  });

  const palette = palettes[themeId];

  useEffect(() => {
    document.documentElement.dataset.theme = themeId;
    window.localStorage.setItem("benchmark-theme", themeId);
  }, [themeId]);

  const antdTheme = useMemo(
    () => ({
      algorithm: theme.darkAlgorithm,
      token: {
        colorPrimary: palette.colorPrimary,
        colorInfo: palette.colorInfo,
        colorSuccess: palette.colorSuccess,
        colorBgBase: palette.colorBgBase,
        colorBgContainer: palette.colorBgContainer,
        colorBorder: palette.colorBorder,
        colorText: palette.colorText,
        colorTextSecondary: palette.colorTextSecondary,
        borderRadius: 18,
        fontFamily: "'Space Grotesk', system-ui, sans-serif",
      },
      components: {
        Card: {
          colorBgContainer: palette.colorBgContainer,
          boxShadowTertiary: "0 24px 70px rgba(0, 0, 0, 0.28)",
          headerBg: "transparent",
        },
        Layout: {
          bodyBg: "transparent",
          headerBg: "transparent",
        },
        Menu: {
          darkItemBg: "transparent",
          darkSubMenuItemBg: "rgba(12, 17, 24, 0.96)",
          itemBg: "transparent",
        },
        Table: {
          colorBgContainer: "transparent",
          headerBg: "rgba(255, 255, 255, 0.045)",
          rowHoverBg: "rgba(73, 199, 255, 0.08)",
        },
      },
    }),
    [palette],
  );

  return (
    <ConfigProvider theme={antdTheme}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <ErrorBoundary>
            <AppShell themeId={themeId} onThemeChange={setThemeId}>
              <Routes>
                <Route path="/" element={<Landing />} />
                <Route path="/tasks/:taskId" element={<TaskDetail />} />
                <Route path="/results" element={<Results />} />
                <Route path="/compare" element={<Compare />} />
                <Route path="/pareto" element={<Pareto />} />
                <Route path="/help" element={<Help />} />
                <Route
                  path="/admin/queue"
                  element={
                    <DevOnly>
                      <Queue />
                    </DevOnly>
                  }
                />
                <Route
                  path="/admin/results"
                  element={
                    <DevOnly>
                      <ResultEditor />
                    </DevOnly>
                  }
                />
                <Route
                  path="/admin/tasks"
                  element={
                    <DevOnly>
                      <TaskEditor />
                    </DevOnly>
                  }
                />
                <Route
                  path="/admin/models"
                  element={
                    <DevOnly>
                      <ModelEditor />
                    </DevOnly>
                  }
                />
                <Route
                  path="/admin/environments"
                  element={
                    <DevOnly>
                      <EnvironmentEditor />
                    </DevOnly>
                  }
                />
                <Route path="*" element={<div>Not Found</div>} />
              </Routes>
            </AppShell>
          </ErrorBoundary>
        </BrowserRouter>
      </QueryClientProvider>
    </ConfigProvider>
  );
}
