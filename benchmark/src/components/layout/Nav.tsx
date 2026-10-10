import { Menu } from "antd";
import { useNavigate, useLocation } from "react-router-dom";
import { useT } from "@/i18n/LanguageProvider";
import type { MessageKey } from "@/i18n/messages";

interface NavItem {
  key: string;
  label: MessageKey;
}

const publicItems: NavItem[] = [
  { key: "/", label: "layout.navLeaderboard" },
  { key: "/overview", label: "layout.navOverview" },
  { key: "/results", label: "layout.navResults" },
  { key: "/compare", label: "layout.navCompare" },
  { key: "/stability", label: "layout.navStability" },
  { key: "/pareto", label: "layout.navPareto" },
  { key: "/agents", label: "layout.navAgents" },
  { key: "/help", label: "layout.navHelp" },
];

const adminItems: NavItem[] = import.meta.env.DEV
  ? [
      { key: "/admin/queue", label: "layout.navQueue" },
      { key: "/admin/results", label: "layout.navResults" },
      { key: "/admin/tasks", label: "layout.navTasks" },
      { key: "/admin/models", label: "layout.navModels" },
      { key: "/admin/environments", label: "layout.navEnvironments" },
      { key: "/admin/harnesses", label: "layout.navHarnesses" },
    ]
  : [];

export function Nav() {
  const navigate = useNavigate();
  const location = useLocation();
  const t = useT();
  const translate = (list: NavItem[]) =>
    list.map((item) => ({ key: item.key, label: t(item.label) }));

  const items = [
    ...translate(publicItems),
    ...(adminItems.length > 0
      ? [{ key: "admin", label: t("layout.navAdmin"), children: translate(adminItems) }]
      : []),
  ];

  return (
    <Menu
      mode="horizontal"
      selectedKeys={[location.pathname]}
      items={items}
      onClick={({ key }) => navigate(key)}
      className="main-nav"
      theme="dark"
    />
  );
}
