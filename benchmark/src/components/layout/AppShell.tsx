import { Suspense, type ReactNode } from "react";
import { Layout } from "antd";
import { Navigate, useLocation } from "react-router-dom";
import { Nav } from "./Nav";
import { ThemeSwitcher } from "./ThemeSwitcher";
import { GlobalFilters } from "@/components/filters/GlobalFilters";
import type { ThemeId } from "@/theme/palettes";

const { Header, Content, Footer } = Layout;

export function DevOnly({ children }: { children: ReactNode }) {
  if (!import.meta.env.DEV) return <Navigate to="/" replace />;
  return <>{children}</>;
}

interface AppShellProps {
  children: ReactNode;
  themeId: ThemeId;
  onThemeChange: (themeId: ThemeId) => void;
}

export function AppShell({ children, themeId, onThemeChange }: AppShellProps) {
  const location = useLocation();
  const isAdmin = location.pathname.startsWith("/admin");
  const shellClass = isAdmin ? "app-shell app-shell--no-ambient" : "app-shell";
  return (
    <Layout className={shellClass}>
      {!isAdmin && (
        <>
          <div className="ambient ambient-a" />
          <div className="ambient ambient-b" />
          <div className="ambient ambient-c" />
        </>
      )}
      <Header className="app-header">
        <span className="brand">
          <span className="brand-mark">r</span>
          <span className="brand-copy">
            <span>benchmark</span>
            <strong>refio</strong>
          </span>
        </span>
        <Nav />
        <Suspense fallback={null}>
          <GlobalFilters />
        </Suspense>
        <ThemeSwitcher value={themeId} onChange={onThemeChange} />
      </Header>
      <Content className="app-content">{children}</Content>
      <Footer className="app-footer">
        <span>
          Tech notes:
          <a href="https://czub.info/" target="_blank" rel="noreferrer">
            Blog
          </a>
        </span>
        <span>
          InteliJ plugin:
          <a
            href="https://plugins.jetbrains.com/plugin/30487-refio/"
            target="_blank"
            rel="noreferrer"
          >
            Refio plugin
          </a>
        </span>
        <span>
          GitHub:
          <a href="https://github.com/shadoq/refio" target="_blank" rel="noreferrer">
            Refio source
          </a>
        </span>
      </Footer>
    </Layout>
  );
}
