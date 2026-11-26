# web cell findings

- GET /api/notes/2 returns bob's private note to any caller: no ownership check
  after the id lookup (IDOR / broken access control, CWE-639).
- POST /api/render echoes the `name` field into the HTML response unescaped:
  reflected XSS (CWE-79).

web_finding = /api/notes/2 | IDOR
web_finding = /api/render | reflected-XSS
flag = FLAG{cells_fanin_both_paths_71c4}
