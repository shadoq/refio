cat > negative/he-textutils-stubs/slugify-keeps-runs/textutils.py <<'EOF'
"""WRONG: slugify replaces each bad character separately instead of collapsing runs."""
import re


def flatten_once(nested):
    out = []
    for item in nested:
        if isinstance(item, list):
            out.extend(item)
        else:
            out.append(item)
    return out


def slugify(title):
    lowered = title.lower()
    dashed = re.sub(r"[^a-z0-9]", "-", lowered)
    return dashed.strip("-")


def mask_email(email):
    local, _, domain = email.partition("@")
    return f"{local[0]}***@{domain}"
EOF
mkdir -p negative/he-textutils-stubs/deep-flatten negative/he-textutils-stubs/slugify-keeps-runs 2>/dev/null; cd /tmp && rm -rf tu && cp -R /home/z/refio-repo/test_data/e2e/fixtures/he-textutils tu && cp /home/z/refio-repo/test_data/e2e/golden/he-textutils-stubs/textutils.py tu/ && cd tu && python3 -m unittest discover -p "test_*.py" 2>&1 | tail -2 && cd /home/z/refio-repo && bash tools/e2e/validate-scenarios.sh he-textutils-stubs 2>&1 | tail -2
__workspace_agent_exit_code=$?
printf "\n<<workspace_agent_exit_code:1794905949452:%s>>\n" "$__workspace_agent_exit_code"
