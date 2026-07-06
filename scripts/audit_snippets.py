#!/usr/bin/env python3
"""Audits the generated snippet corpora in the pgvector store.

Deterministic, no OpenAI key required. Checks:
  1. structure  - TITLE/DESCRIPTION/SOURCE lines present, code fences balanced,
                  sane sizes, https source;
  2. verbatim   - every CODE block of docs-snippets/uisamples-snippets occurs
                  verbatim (modulo whitespace) on its source page;
  3. grounding  - method calls in javaapi usage examples exist among the card's
                  signatures (with an allowlist of common Java methods).

Usage: python3 scripts/audit_snippets.py [--skip-verbatim]
Configuration via env: PGVECTOR_CONTAINER (default jmix-ai-backend-pgvector-1),
PGVECTOR_DB (default vectorstore).
"""
import html
import json
import os
import re
import ssl
import subprocess
import sys
import urllib.request
from collections import Counter, defaultdict

CONTAINER = os.environ.get("PGVECTOR_CONTAINER", "jmix-ai-backend-pgvector-1")
DB = os.environ.get("PGVECTOR_DB", "vectorstore")
CTX = ssl._create_unverified_context()

COMMON_METHODS = {
    "toString", "equals", "hashCode", "getClass", "of", "copyOf", "asList", "format", "formatted",
    "println", "printf", "print", "stream", "map", "filter", "collect", "toList", "forEach", "flatMap",
    "findFirst", "orElse", "orElseGet", "orElseThrow", "isPresent", "isEmpty", "ifPresent", "get", "size",
    "add", "addAll", "remove", "put", "contains", "containsKey", "getOrDefault", "keySet", "values",
    "entrySet", "iterator", "next", "hasNext", "close", "run", "call", "apply", "accept", "test",
    "build", "builder", "create", "getId", "setId", "getName", "setName", "getValue", "setValue",
    "valueOf", "name", "ordinal", "length", "charAt", "substring", "trim", "strip", "split", "join",
    "replace", "toLowerCase", "toUpperCase", "startsWith", "endsWith", "matches", "compareTo", "list",
    "one", "optional", "getBytes", "sorted", "count", "min", "max", "sum", "distinct", "limit", "reduce",
}


def fetch_rows(where):
    sql = ("select json_build_object('id', id, 'meta', metadata, 'content', content) "
           f"from vector_store where {where}")
    out = subprocess.run(
        ["docker", "exec", CONTAINER, "psql", "-U", "postgres", "-d", DB, "-t", "-A", "-c", sql],
        capture_output=True, text=True, check=True).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def norm(text):
    """Whitespace-insensitive normalization for verbatim comparison."""
    return re.sub(r"\s+", "", text)


def strip_html(page):
    page = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", page, flags=re.S | re.I)
    page = re.sub(r"<[^>]+>", " ", page)
    return html.unescape(page)


def fetch_page(url):
    req = urllib.request.Request(url, headers={"User-Agent": "snippet-audit"})
    with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
        return r.read().decode("utf-8", errors="replace")


def code_blocks(content):
    return re.findall(r"\nCODE:\n```[^\n]*\n(.*?)\n```", content, flags=re.S)


def check_structure(rows):
    problems = []
    for row in rows:
        content, rid = row["content"], row["id"]
        issues = []
        if "TITLE: " not in content:
            issues.append("no TITLE")
        if "DESCRIPTION: " not in content:
            issues.append("no DESCRIPTION")
        if "SOURCE: http" not in content:
            issues.append("no https SOURCE")
        if content.count("```") % 2 != 0:
            issues.append("unbalanced code fence")
        title_match = re.search(r"TITLE: (.*)", content)
        if title_match and len(title_match.group(1).strip()) < 8:
            issues.append("title too short")
        desc_match = re.search(r"DESCRIPTION: (.*)", content)
        if desc_match and len(desc_match.group(1).strip()) < 40:
            issues.append("description too short")
        if issues:
            problems.append((rid, row["meta"].get("source", "?"), ", ".join(issues)))
    return problems


def page_text(url):
    """Sample doc endpoints return plain text; docs pages are HTML."""
    raw = fetch_page(url)
    if raw.lstrip().lower().startswith(("<!doctype", "<html")):
        return strip_html(raw)
    return raw


def check_verbatim(rows):
    """Groups snippets by source page, fetches each page once, checks code presence.

    A block is 'augmented' when it is not present verbatim but >=80% of its lines are
    (the model added imports/scaffolding); 'absent' blocks are the real suspects."""
    by_page = defaultdict(list)
    for row in rows:
        meta = row["meta"]
        page_url = meta.get("docUrl") or meta.get("url")  # uisamples content came from docUrl
        if page_url:
            by_page[page_url].append(row)

    flagged, fetch_errors, checked, augmented = [], 0, 0, 0
    for i, (page_url, page_rows) in enumerate(sorted(by_page.items()), 1):
        try:
            page_norm = norm(page_text(page_url))
        except Exception:
            fetch_errors += 1
            continue
        for row in page_rows:
            for block in code_blocks(row["content"]):
                checked += 1
                if norm(block) in page_norm:
                    continue
                lines = [norm(l) for l in block.split("\n") if len(norm(l)) > 5]
                present = sum(1 for l in lines if l in page_norm)
                if lines and present / len(lines) >= 0.8:
                    augmented += 1
                else:
                    flagged.append((row["id"], page_url,
                                    re.sub(r"\s+", " ", block)[:100]))
        if i % 200 == 0:
            print(f"  verbatim: {i}/{len(by_page)} pages", file=sys.stderr)
    return checked, flagged, fetch_errors, augmented


def check_javaapi_grounding(rows):
    flagged, checked = [], 0
    for row in rows:
        blocks = code_blocks(row["content"])
        if not blocks:
            continue
        code = blocks[0]
        if "// Usage example:" not in code:
            continue
        signatures, example = code.split("// Usage example:", 1)
        checked += 1
        called = set(re.findall(r"\.\s*([a-zA-Z_]\w*)\s*\(", example))
        unknown = {m for m in called
                   if m not in COMMON_METHODS
                   and not re.match(r"^(get|set|is|with|add|remove|on|to|from|has)[A-Z_]", m)
                   and m not in signatures}
        if unknown:
            flagged.append((row["id"], row["meta"].get("className", "?"), sorted(unknown)))
    return checked, flagged


def main():
    skip_verbatim = "--skip-verbatim" in sys.argv

    snippet_rows = fetch_rows("metadata->>'type' in ('docs-snippets','uisamples-snippets')")
    javaapi_rows = fetch_rows("metadata->>'type' = 'javaapi'")
    print(f"loaded: {len(snippet_rows)} snippets, {len(javaapi_rows)} javaapi cards")

    print("\n== 1. structure ==")
    structure_problems = check_structure(snippet_rows + javaapi_rows)
    print(f"problems: {len(structure_problems)}")
    for rid, source, issues in structure_problems[:20]:
        print(f"  {rid} [{source}]: {issues}")

    print("\n== 2. javaapi example grounding ==")
    checked, flagged = check_javaapi_grounding(javaapi_rows)
    print(f"checked: {checked} examples, flagged: {len(flagged)}")
    counter = Counter(m for _, _, methods in flagged for m in methods)
    print(f"most common unknown methods: {counter.most_common(15)}")
    for rid, cls, methods in flagged[:15]:
        print(f"  {rid} [{cls}]: {methods}")

    if not skip_verbatim:
        print("\n== 3. code verbatim vs source pages ==")
        checked, flagged, fetch_errors, augmented = check_verbatim(snippet_rows)
        print(f"checked: {checked} code blocks, augmented (>=80% lines on page): {augmented}, "
              f"flagged as absent: {len(flagged)}, page fetch errors: {fetch_errors}")
        for rid, url, preview in flagged[:25]:
            print(f"  {rid} [{url}]: {preview}")
        with open("audit-verbatim-flagged.json", "w") as f:
            json.dump([{"id": r, "page": u, "code": p} for r, u, p in flagged], f, indent=1)
        print("full flagged list: audit-verbatim-flagged.json")


if __name__ == "__main__":
    main()
