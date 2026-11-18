"""Accessibility and integrity checks for signup.html. Run: python checks.py"""
import os
import sys
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.abspath(__file__))
VOID = {"img", "input", "br", "meta", "link", "hr", "source", "area", "base", "col", "embed", "wbr"}
NON_LABELLED_TYPES = {"hidden", "submit", "button", "reset", "image"}

# What the form looked like before the accessibility pass: these must survive.
EXPECTED_NAMES = ["fullname", "email", "password", "country", "plan", "plan", "notes", "terms", "token"]
EXPECTED_IDS = {"logo", "signup-form", "fullname", "email", "password", "country", "plan-free",
                "plan-paid", "notes", "reset-btn"}
EXPECTED_VALUES = {("plan", "free"), ("plan", "paid"), ("terms", "yes"), ("token", "c0ffee")}


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.html_attrs = None
        self.imgs = []
        self.controls = []
        self.buttons = []
        self.labels = []
        self.ids = []
        self.form_attrs = None

    def open_of(self, tag):
        return [e for e in self.stack if e["tag"] == tag]

    def handle_starttag(self, tag, attrs):
        a = {k: (v if v is not None else "") for k, v in attrs}
        el = {"tag": tag, "attrs": a, "text": ""}
        if "id" in a:
            self.ids.append(a["id"])
        if tag == "html":
            self.html_attrs = a
        elif tag == "form":
            self.form_attrs = a
        elif tag == "img":
            self.imgs.append(a)
        elif tag == "label":
            self.labels.append(el)
        elif tag == "legend":
            fieldsets = self.open_of("fieldset")
            if fieldsets:
                fieldsets[-1].setdefault("legends", []).append(el)
        elif tag == "button":
            self.buttons.append(a)
        elif tag in ("input", "select", "textarea"):
            el["wrapping"] = self.open_of("label")
            el["fieldsets"] = self.open_of("fieldset")
            self.controls.append(el)
        if tag not in VOID:
            self.stack.append(el)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i]["tag"] == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        for el in self.stack:
            if el["tag"] in ("label", "legend"):
                el["text"] += data


def main():
    with open(os.path.join(ROOT, "signup.html"), encoding="utf-8") as fh:
        page = Page()
        page.feed(fh.read())
    problems = []

    if not (page.html_attrs or {}).get("lang", "").strip():
        problems.append("<html> has no lang attribute")

    for img in page.imgs:
        src = img.get("src", "")
        if "alt" not in img:
            problems.append(f"image {src} has no alt attribute")
        elif src == "logo.png" and (not img["alt"].strip() or "logo.png" in img["alt"].lower()):
            problems.append("the logo needs a descriptive alt text")
        elif src == "wave.svg" and img["alt"] != "":
            problems.append("the decorative wave must have an empty alt")

    labels_for = {}
    for lab in page.labels:
        if lab["attrs"].get("for") and lab["text"].strip():
            labels_for.setdefault(lab["attrs"]["for"], []).append(lab)
    id_set = set(page.ids)
    for lab in page.labels:
        target = lab["attrs"].get("for")
        if target and target not in id_set:
            problems.append(f"label for='{target}' points to nothing")
    for cid in {i for i in page.ids if page.ids.count(i) > 1}:
        problems.append(f"duplicate id '{cid}'")

    names = []
    for c in page.controls:
        a = c["attrs"]
        typ = a.get("type", "text").lower() if c["tag"] == "input" else c["tag"]
        if a.get("name"):
            names.append(a["name"])
        if typ in NON_LABELLED_TYPES:
            continue
        ok = bool(a.get("aria-label", "").strip())
        if a.get("id") and a["id"] in labels_for:
            ok = True
        if any(w["text"].strip() for w in c["wrapping"]):
            ok = True
        if not ok:
            problems.append(f"control '{a.get('name', '?')}' has no accessible label")

    radios = [c for c in page.controls if c["tag"] == "input" and c["attrs"].get("type") == "radio"]
    for r in radios:
        if not any("".join(l["text"] for l in fs.get("legends", [])).strip() for fs in r["fieldsets"]):
            problems.append("radio buttons must sit in a fieldset with a legend")
            break

    for b in page.buttons:
        if b.get("type", "").lower() not in ("submit", "button", "reset"):
            problems.append("a button has no valid type attribute")
    by_id = {b.get("id"): b for b in page.buttons}
    if any(b.get("id") is None and b.get("type", "").lower() != "submit" for b in page.buttons):
        problems.append("the join button must be a submit button")
    if by_id.get("reset-btn", {}).get("type", "").lower() not in ("reset", "button"):
        problems.append("the clear button must be type reset or button")

    if sorted(names) != sorted(EXPECTED_NAMES):
        problems.append(f"field names changed: {sorted(names)}")
    if not EXPECTED_IDS <= id_set:
        problems.append(f"ids removed or renamed: {sorted(EXPECTED_IDS - id_set)}")
    values = {(c["attrs"].get("name"), c["attrs"].get("value")) for c in page.controls}
    if not EXPECTED_VALUES <= values:
        problems.append("field values changed")
    form = page.form_attrs or {}
    if form.get("action") != "/signup" or form.get("method") != "post":
        problems.append("form action or method changed")

    for p in problems:
        print("FAIL: " + p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
