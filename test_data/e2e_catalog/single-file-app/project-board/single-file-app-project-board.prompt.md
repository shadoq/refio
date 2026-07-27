Build "PROJECT BOARD MINI" - a lightweight project and task management app - as a single
self-contained file `project_board_{{MODEL_ID}}_01.html` (HTML + CSS + vanilla JavaScript inline; no frameworks,
no external libraries, no build step). Use hash routing and persist to localStorage. It must work
by opening the file directly in a browser.

Routes: `#/dashboard`, `#/projects`, `#/project/:id`, `#/tasks`, `#/task/new`, `#/task/:id`,
`#/settings`.

Features:
- Dashboard with summary cards (total projects, open/overdue/completed tasks) and recent tasks.
- Projects CRUD (name, description, status, client, start/due dates).
- Tasks CRUD (title, description, project, assignee, priority, status, due date).
- Kanban board on the project page with columns Todo / In Progress / Review / Done, with
  controls to move tasks between columns.
- Search and filters on the tasks page (by project, status, priority; sort by due date/priority).
- Required-field validation with inline errors.
- Toast notifications and confirm dialogs; empty states.
- Settings: reset demo data, export all data as JSON, import JSON.
- Seed realistic demo data on first load (a few projects, a dozen tasks, some overdue).

Deliver the one file `project_board_{{MODEL_ID}}_01.html`. No console errors.
