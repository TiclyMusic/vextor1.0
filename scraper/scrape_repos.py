#!/usr/bin/env python3
"""
Vextor scraper: cerca su GitHub repository di siti web con UI pulita
(landing page, portfolio, template, dashboard...) e scarica solo i file
HTML / CSS / JS (+ README) di ciascun repo.

Uso:
    export GITHUB_TOKEN=ghp_...            # obbligatorio (rate limit)
    python scraper/scrape_repos.py --max-repos 1200 --out data/raw

Il risultato è una cartella per repo:
    data/raw/<owner>__<repo>/meta.json
    data/raw/<owner>__<repo>/files/<percorso originale>

Lo script è riprendibile: i repo già scaricati vengono saltati.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import quote

import requests

API = "https://api.github.com"
RAW = "https://raw.githubusercontent.com"

# Licenze permissive: di default scarichiamo solo queste, così il dataset
# può essere usato per il training senza problemi.
PERMISSIVE_LICENSES = {
    "mit", "apache-2.0", "bsd-2-clause", "bsd-3-clause", "isc", "unlicense",
    "cc0-1.0", "0bsd", "zlib", "wtfpl", "mit-0", "bsl-1.0", "cc-by-4.0",
}

# Query di ricerca orientate a siti con UI curata. Ogni query restituisce al
# massimo 1000 risultati (limite GitHub), per questo sono tante e vengono
# ulteriormente suddivise per fasce di stelle.
SEARCH_QUERIES = [
    "topic:landing-page",
    "topic:landing-page-template",
    "topic:portfolio-website",
    "topic:portfolio-template",
    "topic:personal-website",
    "topic:website-template",
    "topic:html-template",
    "topic:html5-template",
    "topic:css-template",
    "topic:responsive-website",
    "topic:responsive-design",
    "topic:modern-ui",
    "topic:clean-ui",
    "topic:minimal-design",
    "topic:glassmorphism",
    "topic:neumorphism",
    "topic:ui-design",
    "topic:web-design",
    "topic:frontend-mentor",
    "topic:html-css-javascript",
    "topic:html-css-js",
    "topic:vanilla-javascript website",
    "topic:tailwindcss landing",
    "topic:tailwindcss template",
    "topic:bootstrap5 template",
    "topic:dashboard-template",
    "topic:admin-dashboard html",
    "topic:restaurant-website",
    "topic:agency-website",
    "topic:startup-website",
    "topic:saas-landing-page",
    "topic:app-landing-page",
    "topic:ecommerce-website html",
    "topic:blog-template",
    "topic:resume-website",
    "topic:onepage",
    "topic:one-page-template",
    "landing page in:name,description",
    "portfolio template in:name,description",
    "clean website template in:name,description",
    "minimal website in:name,description",
    "modern website html css in:name,description",
    "responsive website html css js in:description",
]

STAR_RANGES = ["stars:>=200", "stars:50..199", "stars:15..49", "stars:5..14"]
LANGS = ["language:HTML", "language:CSS", "language:JavaScript"]

ALLOWED_EXT = {".html", ".htm", ".css", ".js"}
README_RE = re.compile(r"^readme(\.md|\.markdown|\.txt)?$", re.I)
SKIP_DIRS = re.compile(
    r"(^|/)(node_modules|vendor|vendors|bower_components|\.git|\.github|dist/lib|"
    r"lib|libs|plugins|third[-_]?party|assets/vendor|static/vendor|coverage|"
    r"test|tests|__tests__|docs/api|build/static/js|\.next|\.nuxt|out)(/|$)",
    re.I,
)
# Librerie note che non vogliamo nel dataset (sono codice di terze parti).
SKIP_FILES = re.compile(
    r"^(jquery|bootstrap(\.bundle)?|popper|swiper|slick|owl\.carousel|aos|gsap|"
    r"three|chart|lodash|moment|font-?awesome|animate|normalize|reset|"
    r"tailwind(\.output)?|wow|isotope|magnific|lightbox|fancybox|particles|"
    r"typed|scrollreveal|splide|glide|anime|lottie|feather|ionicons|"
    r"modernizr|polyfill|webfont|analytics|gtag)[\w.-]*\.(js|css)$",
    re.I,
)
MAX_FILE_BYTES = 200_000
MAX_REPO_BYTES = 2_000_000
MAX_FILES_PER_REPO = 150
MAX_HTML_PAGES = 8
MAX_REPO_SIZE_KB = 150_000  # campo "size" dell'API (KB): evita repo enormi


class GitHub:
    def __init__(self, token: str | None):
        self.s = requests.Session()
        self.s.headers.update({
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "vextor-scraper",
        })
        if token:
            self.s.headers["Authorization"] = f"Bearer {token}"
        self.last_search = 0.0

    def get(self, url: str, params=None, search=False, retries=6):
        for attempt in range(retries):
            if search:
                # Search API: 30 richieste/minuto con token.
                wait = 2.2 - (time.time() - self.last_search)
                if wait > 0:
                    time.sleep(wait)
                self.last_search = time.time()
            try:
                r = self.s.get(url, params=params, timeout=30)
            except requests.RequestException as e:
                print(f"  rete: {e}; ritento...", file=sys.stderr)
                time.sleep(2 ** attempt)
                continue
            if r.status_code in (403, 429):
                reset = r.headers.get("X-RateLimit-Reset")
                retry_after = r.headers.get("Retry-After")
                if retry_after:
                    sleep_s = int(retry_after) + 1
                elif reset and r.headers.get("X-RateLimit-Remaining") == "0":
                    sleep_s = max(5, int(reset) - int(time.time()) + 2)
                else:
                    sleep_s = 30 * (attempt + 1)
                print(f"  rate limit ({r.status_code}), attendo {sleep_s}s", file=sys.stderr)
                time.sleep(min(sleep_s, 900))
                continue
            if r.status_code >= 500:
                time.sleep(2 ** attempt)
                continue
            return r
        return None


def search_candidates(gh: GitHub, max_candidates: int, licenses: set[str] | None,
                      min_stars: int) -> list[dict]:
    seen: dict[str, dict] = {}
    for q in SEARCH_QUERIES:
        for lang in LANGS:
            for stars in STAR_RANGES:
                lo = int(re.findall(r"\d+", stars)[0])
                if lo < min_stars and ".." in stars:
                    continue
                query = f"{q} {lang} {stars} fork:false archived:false"
                for page in range(1, 11):
                    r = gh.get(f"{API}/search/repositories", search=True, params={
                        "q": query, "sort": "stars", "order": "desc",
                        "per_page": 100, "page": page,
                    })
                    if r is None or r.status_code != 200:
                        break
                    items = r.json().get("items", [])
                    for it in items:
                        name = it["full_name"]
                        if name in seen:
                            continue
                        lic = ((it.get("license") or {}).get("key") or "").lower()
                        if licenses is not None and lic not in licenses:
                            continue
                        if it.get("size", 0) > MAX_REPO_SIZE_KB:
                            continue
                        seen[name] = {
                            "full_name": name,
                            "html_url": it["html_url"],
                            "description": it.get("description") or "",
                            "topics": it.get("topics") or [],
                            "stars": it.get("stargazers_count", 0),
                            "language": it.get("language"),
                            "license": lic,
                            "homepage": it.get("homepage") or "",
                            "default_branch": it.get("default_branch") or "main",
                            "pushed_at": it.get("pushed_at"),
                        }
                    print(f"[search] {query!r} p{page}: +{len(items)} (totale unici {len(seen)})")
                    if len(items) < 100:
                        break
                    if len(seen) >= max_candidates:
                        break
                if len(seen) >= max_candidates:
                    break
            if len(seen) >= max_candidates:
                break
        if len(seen) >= max_candidates:
            break
    cands = sorted(seen.values(), key=lambda x: x["stars"], reverse=True)
    return cands


def pick_files(tree: list[dict]) -> tuple[list[dict], dict | None]:
    files, readme = [], None
    for node in tree:
        if node.get("type") != "blob":
            continue
        path = node["path"]
        base = path.rsplit("/", 1)[-1]
        if "/" not in path and README_RE.match(base):
            readme = node
            continue
        ext = os.path.splitext(base)[1].lower()
        if ext not in ALLOWED_EXT:
            continue
        if SKIP_DIRS.search(path) or SKIP_FILES.search(base):
            continue
        if ".min." in base or base.endswith(".map"):
            continue
        size = node.get("size", 0)
        if size == 0 or size > MAX_FILE_BYTES:
            continue
        files.append(node)
    # Al massimo MAX_HTML_PAGES pagine HTML (index in testa, percorsi brevi
    # prima); poi CSS/JS ordinati per profondità, così le pagine scelte hanno
    # quasi sempre i loro fogli di stile e script.
    def is_html(n):
        return n["path"].lower().endswith((".html", ".htm"))

    def html_prio(n):
        p = n["path"].lower()
        return (0 if p.rsplit("/", 1)[-1] == "index.html" else 1, p.count("/"), len(p))

    pages = sorted((n for n in files if is_html(n)), key=html_prio)[:MAX_HTML_PAGES]
    assets = sorted((n for n in files if not is_html(n)),
                    key=lambda n: (n["path"].count("/"), n["path"].endswith(".js"), len(n["path"])))
    chosen, total = [], 0
    for n in pages + assets:
        if len(chosen) >= MAX_FILES_PER_REPO or total + n["size"] > MAX_REPO_BYTES:
            continue
        chosen.append(n)
        total += n["size"]
    return chosen, readme


def fetch_raw(session: requests.Session, full_name: str, ref: str, path: str) -> bytes | None:
    url = f"{RAW}/{full_name}/{quote(ref)}/{quote(path)}"
    for attempt in range(3):
        try:
            r = session.get(url, timeout=30)
            if r.status_code == 200:
                return r.content
            if r.status_code == 404:
                return None
        except requests.RequestException:
            pass
        time.sleep(1 + attempt * 2)
    return None


def download_repo(gh: GitHub, raw_session: requests.Session, cand: dict, out_dir: Path) -> bool:
    name = cand["full_name"]
    dest = out_dir / name.replace("/", "__")
    if (dest / "meta.json").exists():
        return True
    r = gh.get(f"{API}/repos/{name}/git/trees/{quote(cand['default_branch'])}",
               params={"recursive": "1"})
    if r is None or r.status_code != 200:
        print(f"  [skip] {name}: albero non disponibile")
        return False
    data = r.json()
    sha = data.get("sha") or cand["default_branch"]
    files, readme = pick_files(data.get("tree", []))
    if not any(f["path"].lower().endswith((".html", ".htm")) for f in files):
        print(f"  [skip] {name}: nessun HTML")
        return False

    saved = []
    for node in files:
        content = fetch_raw(raw_session, name, sha, node["path"])
        if content is None:
            continue
        target = dest / "files" / node["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        saved.append(node["path"])
    readme_text = ""
    if readme:
        content = fetch_raw(raw_session, name, sha, readme["path"])
        if content:
            readme_text = content.decode("utf-8", "replace")[:6000]
    if not saved:
        return False
    meta = dict(cand, sha=sha, files=saved, readme=readme_text)
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), "utf-8")
    print(f"  [ok] {name}: {len(saved)} file")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="data/raw", help="cartella di output")
    ap.add_argument("--max-repos", type=int, default=1200, help="quanti repo scaricare")
    ap.add_argument("--candidates", type=int, default=0,
                    help="quanti candidati raccogliere dalla ricerca (default: 2.5x max-repos)")
    ap.add_argument("--min-stars", type=int, default=5)
    ap.add_argument("--any-license", action="store_true",
                    help="includi anche repo senza licenza permissiva (sconsigliato)")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("ATTENZIONE: GITHUB_TOKEN non impostato, il rate limit sarà molto basso.", file=sys.stderr)
    gh = GitHub(token)
    raw_session = requests.Session()
    raw_session.headers["User-Agent"] = "vextor-scraper"

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cand_file = out.parent / "candidates.json"
    if cand_file.exists():
        cands = json.loads(cand_file.read_text("utf-8"))
        print(f"Riprendo da {cand_file} ({len(cands)} candidati)")
    else:
        n = args.candidates or int(args.max_repos * 2.5)
        cands = search_candidates(gh, n, None if args.any_license else PERMISSIVE_LICENSES, args.min_stars)
        cand_file.write_text(json.dumps(cands, ensure_ascii=False, indent=1), "utf-8")
        print(f"Salvati {len(cands)} candidati in {cand_file}")

    done = sum(1 for p in out.iterdir() if (p / "meta.json").exists())
    print(f"Già scaricati: {done}")
    todo = [c for c in cands if not (out / c["full_name"].replace("/", "__") / "meta.json").exists()]
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        it = iter(todo)
        running = set()
        while done < args.max_repos:
            while len(running) < args.workers:
                c = next(it, None)
                if c is None:
                    break
                running.add(ex.submit(download_repo, gh, raw_session, c, out))
            if not running:
                break
            fut = next(as_completed(running))
            running.remove(fut)
            try:
                if fut.result():
                    done += 1
            except Exception as e:  # un repo rotto non deve fermare tutto
                print(f"  [errore] {e}", file=sys.stderr)
        for f in running:
            f.cancel()
    print(f"\nFatto: {done} repo in {out}")


if __name__ == "__main__":
    main()
