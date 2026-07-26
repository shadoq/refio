import { Menu } from "antd";
import { useNavigate, useLocation } from "react-router-dom";

const publicItems = [
  { key: "/", label: "Leaderboard" },
  { key: "/results", label: "Results" },
  { key: "/compare", label: "Compare" },
  { key: "/pareto", label: "Pareto" },
  { key: "/help", label: "Help" },
];

const adminItems = import.meta.env.DEV
  ? [
      { key: "/admin/queue", label: "Queue" },
      { key: "/admin/results", label: "Results" },
      { key: "/admin/tasks", label: "Tasks" },
      { key: "/admin/models", label: "Models" },
      { key: "/admin/environments", label: "Environments" },
    ]
  : [];

export function Nav() {
  const navigate = useNavigate();
  const location = useLocation();

  const items = [
    ...publicItems,
    ...(adminItems.length > 0
      ? [{ key: "admin", label: "Admin", children: adminItems }]
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
