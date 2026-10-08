#!/usr/bin/env python3
"""
Costruisce il dataset di training (JSONL in formato chat) a partire dai repo
scaricati da scrape_repos.py.

Per ogni pagina HTML:
  1. inline di CSS e JS locali  -> un unico file HTML autosufficiente
  2. pulizia (commenti, analytics, base64, email) e immagini -> placeholder
  3. filtro qualità "UI pulita" (responsive, flex/grid, tag semantici, ...)
  4. generazione di una richiesta in linguaggio naturale (IT/EN) che
     descrive il sito: è l'input che l'utente scriverebbe nell'app.

Uso:
    python scraper/build_dataset.py --raw data/raw --out data/dataset
Output:
    data/dataset/train.jsonl, data/dataset/val.jsonl, data/dataset/stats.json
"""
from __future__ import annotations

import argparse
import colorsys
import hashlib
import json
import posixpath
import random
import re
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path

SYSTEM_PROMPT = (
    "Sei Vextor, un assistente esperto di web design. Quando l'utente chiede un sito, "
    "rispondi con UN SOLO file HTML completo dentro un blocco ```html, con CSS in <style> "
    "e JavaScript in <script>. Il design deve essere pulito, moderno, responsive e accessibile. "
    "Usa immagini placeholder da https://picsum.photos quando servono."
)

IMG_EXT = r"(?:png|jpe?g|gif|webp|avif|svg|bmp|ico)"
CDN_FALLBACK_CSS = {
    "bootstrap": "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css",
    "font-awesome": "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css",
    "fontawesome": "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css",
    "all.min": "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css",
    "normalize": "https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css",
    "animate": "https://cdnjs.cloudflare.com/ajax/libs/animate.css/4.1.1/animate.min.css",
    "aos": "https://unpkg.com/aos@2.3.4/dist/aos.css",
    "swiper": "https://cdn.jsdelivr.net/npm/swiper@11/swiper-bundle.min.css",
    "boxicons": "https://unpkg.com/boxicons@2.1.4/css/boxicons.min.css",
}
CDN_FALLBACK_JS = {
    "jquery": "https://code.jquery.com/jquery-3.7.1.min.js",
    "bootstrap": "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js",
    "aos": "https://unpkg.com/aos@2.3.4/dist/aos.js",
    "swiper": "https://cdn.jsdelivr.net/npm/swiper@11/swiper-bundle.min.js",
    "gsap": "https://cdnjs.cloudflare.com/ajax/libs/gsap/3.12.5/gsap.min.js",
    "typed": "https://cdn.jsdelivr.net/npm/typed.js@2.1.0/dist/typed.umd.js",
    "scrollreveal": "https://unpkg.com/scrollreveal@4.0.9/dist/scrollreveal.min.js",
}

VENDOR_CSS = re.compile(
    r"(\.min\.css$|(^|/)(vendor|vendors|lib|libs|plugins?|third[-_]?party)/|"
    r"owl|slick|carousel|icon|icofont|themify|materialdesign|glightbox|magnific|fancybox|"
    r"meanmenu|sweetalert|animate|reset|normalize|font|aos|swiper|lightbox|nice-select|"
    r"bootstrap|tailwind-runtime|splide|flickity|venobox|boxicons|remixicon|line-?awesome|"
    r"simple-line|feather|hamburgers|preloader|cursor)",
    re.I,
)

# Parole chiave -> tipo di sito (it, en)
SITE_KINDS = [
    (r"weather|calculator|todo|quiz|game|clock|timer", ("una piccola web app", "a small web app")),
    (r"portfolio|resume|cv\b|personal[- ]website|about[- ]me", ("un sito portfolio personale", "a personal portfolio website")),
    (r"restaurant|food|cafe|coffee|pizza|bakery|menu|ristorante|recipe", ("un sito per un ristorante", "a restaurant website")),
    (r"dashboard|admin", ("una dashboard di amministrazione", "an admin dashboard")),
    (r"e-?commerce|shop|store|product|cart|negozio", ("un sito e-commerce", "an e-commerce website")),
    (r"blog|article|news|magazine", ("un blog", "a blog")),
    (r"agency|studio|creative|marketing|digital", ("il sito di un'agenzia creativa", "a creative agency website")),
    (r"saas|software|startup|app\b|mobile app|product launch", ("una landing page per un prodotto SaaS", "a SaaS product landing page")),
    (r"fitness|gym|workout|yoga|sport", ("un sito per una palestra", "a gym / fitness website")),
    (r"travel|tour|hotel|booking|viaggi", ("un sito di viaggi", "a travel website")),
    (r"real[- ]estate|property|house|immobil", ("un sito immobiliare", "a real estate website")),
    (r"education|course|school|learn|university", ("un sito per corsi online", "an online courses website")),
    (r"music|band|artist|podcast", ("il sito di un artista musicale", "a musician website")),
    (r"photograph|gallery|photo", ("un sito di fotografia", "a photography website")),
    (r"crypto|nft|web3|finance|bank|fintech", ("una landing page fintech", "a fintech landing page")),
    (r"medical|clinic|doctor|health|dental", ("il sito di una clinica", "a medical clinic website")),
    (r"wedding|event|conference", ("il sito di un evento", "an event website")),
    (r"login|sign[- ]?up|form|auth", ("una pagina di login", "a login page")),
    (r"landing|one[- ]?page|homepage|template|website", ("una landing page", "a landing page")),
]

FEATURES = [
    (r"classList\.toggle\([^)]*(menu|nav|open|active|show)", ("menu mobile a scomparsa", "mobile hamburger menu")),
    (r"(dark[-_ ]?mode|theme[-_ ]?toggle|data-theme)", ("toggle tema chiaro/scuro", "light/dark theme toggle")),
    (r"IntersectionObserver|AOS\.init|ScrollReveal|data-aos", ("animazioni allo scroll", "scroll animations")),
    (r"(slider|carousel|swiper|slide)", ("slider / carosello", "slider / carousel")),
    (r"<form", ("un form di contatto", "a contact form")),
    (r"(accordion|faq)", ("sezione FAQ ad accordion", "FAQ accordion")),
    (r"(modal|popup|lightbox)", ("finestra modale", "modal dialog")),
    (r"(pricing|price|plan)", ("tabella prezzi", "pricing table")),
    (r"(testimonial|review)", ("testimonianze", "testimonials")),
    (r"(countdown|counter)", ("contatori animati", "animated counters")),
    (r"position:\s*sticky|navbar.*fixed|header.*fixed", ("navbar fissa", "sticky navbar")),
]

NAMED_COLORS_IT = {
    "blu": (40, 90, 220), "azzurro": (60, 170, 240), "viola": (130, 70, 220), "rosa": (235, 90, 160),
    "rosso": (220, 50, 50), "arancione": (245, 140, 40), "giallo": (240, 200, 40),
    "verde": (40, 170, 90), "turchese": (30, 190, 180), "marrone": (130, 85, 50),
    "nero": (15, 15, 20), "bianco": (250, 250, 250), "grigio": (130, 130, 140), "beige": (230, 215, 185),
    "blu notte": (20, 30, 70),
}
IT2EN = {"blu": "blue", "azzurro": "light blue", "viola": "purple", "rosa": "pink", "rosso": "red",
         "arancione": "orange", "giallo": "yellow", "verde": "green", "turchese": "teal",
         "marrone": "brown", "nero": "black", "bianco": "white", "grigio": "gray", "beige": "beige",
         "blu notte": "navy"}


class PageInfo(HTMLParser):
    """Estrae titolo, headings, testi di nav/bottoni e testo visibile."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.meta_desc = ""
        self.h1: list[str] = []
        self.h2: list[str] = []
        self.nav: list[str] = []
        self.buttons: list[str] = []
        self.text_len = 0
        self._stack: list[str] = []
        self._buf = ""
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in ("script", "style", "noscript", "svg"):
            self._skip += 1
        if tag == "meta" and (a.get("name") or "").lower() == "description":
            self.meta_desc = (a.get("content") or "").strip()
        if tag in ("title", "h1", "h2", "a", "button"):
            self._buf = ""
        self._stack.append(tag)

    def handle_endtag(self, tag):
        if tag in ("script", "style", "noscript", "svg") and self._skip:
            self._skip -= 1
        text = re.sub(r"\s+", " ", self._buf).strip()
        if text and len(text) < 120:
            if tag == "title":
                self.title = text
            elif tag == "h1":
                self.h1.append(text)
            elif tag == "h2":
                self.h2.append(text)
            elif tag == "a" and "nav" in self._stack:
                self.nav.append(text)
            elif tag == "button":
                self.buttons.append(text)
        if tag in ("title", "h1", "h2", "a", "button"):
            self._buf = ""
        while self._stack:
            if self._stack.pop() == tag:
                break

    def handle_data(self, data):
        if self._skip:
            return
        self._buf += data
        self.text_len += len(data.strip())


# --------------------------------------------------------------------------- inlining

def is_local(url: str) -> bool:
    u = url.strip().lower()
    return bool(u) and not u.startswith(("http:", "https:", "//", "data:", "mailto:", "tel:", "#", "javascript:", "{{", "${"))


def resolve(page_dir: str, url: str) -> str:
    url = url.split("?")[0].split("#")[0]
    if url.startswith("/"):
        return posixpath.normpath(url.lstrip("/"))
    return posixpath.normpath(posixpath.join(page_dir, url))


def read_text(p: Path) -> str | None:
    try:
        data = p.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:2000]:
        return None
    return data.decode("utf-8", "replace")


def cdn_for(path: str, table: dict[str, str]) -> str | None:
    base = path.rsplit("/", 1)[-1].lower()
    for key, url in table.items():
        if base.startswith(key):
            return url
    return None


def inline_css_imports(css: str, css_path: str, files_dir: Path, depth=0) -> str:
    if depth > 2:
        return css

    def repl(m):
        url = m.group(1) or m.group(2)
        if not is_local(url):
            return m.group(0)
        p = resolve(posixpath.dirname(css_path), url)
        txt = read_text(files_dir / p)
        if txt is None:
            return ""
        return inline_css_imports(txt, p, files_dir, depth + 1)

    return re.sub(r"""@import\s+(?:url\(\s*['"]?([^'")]+)['"]?\s*\)|['"]([^'"]+)['"])[^;]*;""", repl, css)


ATTR_RE = r"""\b{name}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"""


def get_attr(tag: str, name: str) -> str | None:
    m = re.search(ATTR_RE.format(name=name), tag, re.I)
    if not m:
        return None
    return next(g for g in m.groups() if g is not None)


class SkipPage(Exception):
    pass


def inline_page(html: str, page_path: str, files_dir: Path) -> str:
    page_dir = posixpath.dirname(page_path)

    def link_repl(m):
        tag = m.group(0)
        rel = (get_attr(tag, "rel") or "").lower()
        href = get_attr(tag, "href") or ""
        if "stylesheet" in rel:
            if not is_local(href):
                return tag
            p = resolve(page_dir, href)
            css = read_text(files_dir / p)
            if css is None:
                cdn = cdn_for(p, CDN_FALLBACK_CSS)
                if cdn:
                    return f'<link rel="stylesheet" href="{cdn}">'
                if VENDOR_CSS.search(p):
                    return ""  # libreria accessoria (icone, caroselli...): si può togliere
                # senza il suo CSS principale la pagina non rappresenta il design reale
                raise SkipPage(f"CSS mancante: {p}")
            css = inline_css_imports(css, p, files_dir)
            css = fix_css_urls(css)
            return f"<style>\n{css.strip()}\n</style>"
        if any(k in rel for k in ("icon", "manifest", "apple-touch", "preload", "prefetch")) and is_local(href):
            return ""
        return tag

    html = re.sub(r"<link\b[^>]*>", link_repl, html, flags=re.I)

    def script_repl(m):
        open_tag, body = m.group(1), m.group(2)
        src = get_attr(open_tag, "src")
        if src is None:
            if re.search(r"""\bimport\s[^;]*from\s*['"]\.{0,2}/""", body):
                raise SkipPage("module con import locali")
            return m.group(0)
        if not is_local(src):
            return m.group(0)
        p = resolve(page_dir, src)
        js = read_text(files_dir / p)
        if js is None:
            cdn = cdn_for(p, CDN_FALLBACK_JS)
            return f'<script src="{cdn}"></script>' if cdn else ""
        if re.search(r"""\bimport\s[^;]*from\s*['"]\.{0,2}/|\brequire\(['"]\.""", js):
            raise SkipPage("JS modulare / bundler")
        js = js.replace("</script", "<\\/script")
        is_module = (get_attr(open_tag, "type") or "").lower() == "module"
        attr = ' type="module"' if is_module else ""
        return f"<script{attr}>\n{js.strip()}\n</script>"

    html = re.sub(r"(<script\b[^>]*>)(.*?)</script\s*>", script_repl, html, flags=re.I | re.S)
    html = re.sub(r"(<style\b[^>]*>)(.*?)(</style>)",
                  lambda m: m.group(1) + fix_css_urls(m.group(2)) + m.group(3), html, flags=re.I | re.S)
    return html


_img_counter = [0]


def placeholder(w=1200, h=800) -> str:
    _img_counter[0] += 1
    return f"https://picsum.photos/seed/v{_img_counter[0] % 997}/{w}/{h}"


def fix_css_urls(css: str) -> str:
    def repl(m):
        url = m.group(1).strip("'\" ")
        if is_local(url) and re.search(rf"\.{IMG_EXT}$", url.split("?")[0], re.I):
            return f"url('{placeholder(1600, 900)}')"
        return m.group(0)
    return re.sub(r"url\(([^)]*)\)", repl, css)


def fix_assets(html: str) -> str:
    def src_repl(m):
        attr, q, url = m.group(1), m.group(2), m.group(3)
        if is_local(url) and re.search(rf"\.{IMG_EXT}$", url.split("?")[0], re.I):
            return f"{attr}={q}{placeholder()}{q}"
        return m.group(0)
    html = re.sub(r"""\b(src|data-src|poster|href)=(["'])([^"']+)\2""", src_repl, html, flags=re.I)
    html = re.sub(r"""\s+srcset=(["'])[^"']*\1""", "", html, flags=re.I)
    # inline style="background-image:url(img/x.jpg)"
    html = re.sub(r"""(style=["'][^"']*)url\(([^)]*)\)""",
                  lambda m: m.group(1) + fix_css_urls(f"url({m.group(2)})"), html, flags=re.I)
    return html


# --------------------------------------------------------------------------- CSS non usato

def _split_top(text: str, sep: str) -> list[str]:
    """Divide su `sep` ignorando parentesi e stringhe (per liste di selettori)."""
    out, depth, cur, quote = [], 0, [], ""
    for ch in text:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = ""
            continue
        if ch in "\"'":
            quote = ch
        elif ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == sep and depth == 0:
            out.append("".join(cur))
            cur = []
            continue
        cur.append(ch)
    out.append("".join(cur))
    return out


def _css_blocks(css: str):
    """Genera (prelude, body) dei blocchi di primo livello; None se il CSS è malformato."""
    i, n = 0, len(css)
    blocks = []
    while i < n:
        j = css.find("{", i)
        if j < 0:
            break
        prelude = css[i:j]
        depth, k, quote = 1, j + 1, ""
        while k < n and depth:
            ch = css[k]
            if quote:
                if ch == quote and css[k - 1] != "\\":
                    quote = ""
            elif ch in "\"'":
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            k += 1
        if depth:
            return None
        # @import/@charset senza blocco prima del selettore
        stmts = prelude.split(";")
        for st in stmts[:-1]:
            if st.strip():
                blocks.append((st.strip() + ";", None))
        blocks.append((stmts[-1].strip(), css[j + 1:k - 1]))
        i = k
    return blocks


SEL_TOKEN = re.compile(r"([.#])(-?[A-Za-z_][\w-]*)")


def prune_css(css: str, used: set[str]) -> str:
    """Rimuove le regole i cui selettori usano classi/id assenti dalla pagina."""
    blocks = _css_blocks(css)
    if blocks is None:
        return css
    out = []
    for prelude, body in blocks:
        if body is None:
            out.append(prelude)
            continue
        low = prelude.lower()
        if low.startswith(("@media", "@supports", "@layer", "@container")):
            inner = prune_css(body, used)
            if inner.strip():
                out.append(f"{prelude} {{\n{inner}\n}}")
            continue
        if low.startswith("@"):
            out.append(f"{prelude} {{{body}}}")
            continue
        kept = []
        for sel in _split_top(prelude, ","):
            # i token ".5" di valori tipo "0.5" non stanno nei selettori
            names = [m.group(2) for m in SEL_TOKEN.finditer(re.sub(r"\[[^\]]*\]|\([^)]*\)", "", sel))]
            if all(name in used for name in names):
                kept.append(sel.strip())
        if kept:
            out.append(", ".join(kept) + " {" + body + "}")
    return "\n".join(out)


def used_names(html: str) -> set[str]:
    used = set()
    for m in re.finditer(r"""\b(?:class|id|for|data-[\w-]+)\s*=\s*["']([^"']*)["']""", html, re.I):
        used.update(m.group(1).split())
    # classi/id usati dal JavaScript (classList, querySelector, stringhe...)
    for m in re.finditer(r"<script\b[^>]*>(.*?)</script>", html, re.I | re.S):
        used.update(re.findall(r"[A-Za-z_][\w-]*", m.group(1)))
    return used


def prune_unused_css(html: str) -> str:
    used = used_names(html)
    return re.sub(r"(<style\b[^>]*>)(.*?)(</style>)",
                  lambda m: m.group(1) + "\n" + prune_css(m.group(2), used).strip() + "\n" + m.group(3),
                  html, flags=re.I | re.S)


def normalize_indent(text: str) -> str:
    """Riporta l'indentazione a 2 spazi per livello (meno token, stesso codice)."""
    lines = text.replace("\t", "    ").splitlines()
    steps = Counter()
    prev = 0
    for line in lines:
        if not line.strip():
            continue
        ind = len(line) - len(line.lstrip(" "))
        if ind > prev:
            steps[ind - prev] += 1
        prev = ind
    unit = steps.most_common(1)[0][0] if steps else 2
    if unit <= 2:
        return "\n".join(lines)
    out = []
    for line in lines:
        ind = len(line) - len(line.lstrip(" "))
        out.append(" " * min(2 * (ind // unit), 24) + line.lstrip(" "))
    return "\n".join(out)


def clean(html: str) -> str:
    html = re.sub(r"<!--(?!\[if).*?-->", "", html, flags=re.S)
    # analytics / tracker
    html = re.sub(r"<script[^>]*(googletagmanager|google-analytics|gtag\(|hotjar|clarity\.ms)[^>]*>.*?</script>",
                  "", html, flags=re.I | re.S)
    html = re.sub(r"<script>[^<]*(gtag\(|dataLayer|_gaq|fbq\()[^<]*</script>", "", html, flags=re.I | re.S)
    # data URI enormi
    html = re.sub(r"data:[\w/+.-]+;base64,[A-Za-z0-9+/=]{300,}", placeholder(400, 400), html)
    # email -> placeholder (privacy)
    html = re.sub(r"(?<![/\w.+-])[\w.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b", "hello@example.com", html)
    html = re.sub(r"""href=(["'])tel:[^"']*\1""", 'href="tel:+390000000000"', html, flags=re.I)
    # commenti CSS (header di sezione, licenze): solo token sprecati
    html = re.sub(r"(<style\b[^>]*>)(.*?)(</style>)",
                  lambda m: m.group(1) + re.sub(r"/\*.*?\*/", "", m.group(2), flags=re.S) + m.group(3),
                  html, flags=re.I | re.S)
    # SVG inline enormi -> icona semplice
    html = re.sub(r"<svg\b(?:(?!</svg>).){3000,}?</svg>",
                  '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">'
                  '<circle cx="12" cy="12" r="10" fill="currentColor"/></svg>', html, flags=re.S)
    html = prune_unused_css(html)
    html = normalize_indent(html)
    # spazi
    html = "\n".join(line.rstrip() for line in html.splitlines())
    html = re.sub(r"\n{3,}", "\n\n", html)
    return html.strip() + "\n"


# --------------------------------------------------------------------------- qualità

def quality_score(html: str) -> tuple[int, dict]:
    low = html.lower()
    f = {
        "viewport": 'name="viewport"' in low or "name='viewport'" in low,
        "media_query": "@media" in low,
        "flex": "display: flex" in low or "display:flex" in low or "flex-direction" in low,
        "grid": "display: grid" in low or "display:grid" in low or "grid-template" in low,
        "css_vars": bool(re.search(r"--[\w-]+\s*:", html)) and "var(--" in low,
        "transition": "transition" in low or "@keyframes" in low,
        "hover": ":hover" in low,
        "web_font": "fonts.googleapis" in low or "font-family" in low,
        "tailwind": "cdn.tailwindcss.com" in low or bool(re.search(r'class="[^"]*\b(flex|grid|px-\d|py-\d|text-\w+-\d00)\b', html)),
    }
    semantic = sum(t in low for t in ("<header", "<nav", "<main", "<section", "<footer", "<article"))
    score = sum(2 if k in ("viewport", "media_query") else 1 for k, v in f.items() if v)
    score += min(semantic, 3)
    if f["tailwind"]:
        score += 2  # tailwind gestisce responsive/flex senza @media espliciti
    bad = sum(t in low for t in ("<font", "<center", "bgcolor=", "<marquee", "<frameset"))
    score -= 3 * bad
    if low.count("<table") > 3:
        score -= 2
    if low.count(' style="') > 40:
        score -= 1
    f["semantic"] = semantic
    return score, f


# --------------------------------------------------------------------------- istruzioni

def hex_to_rgb(h: str):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    if len(h) not in (6, 8):
        return None
    try:
        return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    except ValueError:
        return None


def nearest_color_name(rgb) -> str:
    return min(NAMED_COLORS_IT, key=lambda n: sum((a - b) ** 2 for a, b in zip(NAMED_COLORS_IT[n], rgb)))


def palette(html: str) -> list[str]:
    cols = Counter()
    for m in re.finditer(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b", html):
        rgb = hex_to_rgb(m.group(1))
        if rgb:
            cols[nearest_color_name(rgb)] += 1
    for m in re.finditer(r"rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)", html):
        cols[nearest_color_name(tuple(int(x) for x in m.groups()))] += 1
    for m in re.finditer(r"hsla?\(\s*(\d+(?:\.\d+)?)(?:deg)?\s*,\s*(\d+(?:\.\d+)?)%\s*,\s*(\d+(?:\.\d+)?)%", html):
        h, sat, li = (float(x) for x in m.groups())
        r, g, b = colorsys.hls_to_rgb((h % 360) / 360, li / 100, sat / 100)
        cols[nearest_color_name((r * 255, g * 255, b * 255))] += 1
    return [c for c, _ in cols.most_common(6) if c not in ("bianco", "nero", "grigio")][:3]


def is_dark(html: str) -> bool:
    m = re.search(r"body\s*{[^}]*?background(?:-color)?\s*:\s*([^;}]+)", html)
    if not m:
        return False
    v = m.group(1).strip()
    var = re.match(r"var\((--[\w-]+)", v)
    if var:
        d = re.search(re.escape(var.group(1)) + r"\s*:\s*([^;}]+)", html)
        v = d.group(1).strip() if d else ""
    hsl = re.match(r"hsla?\(\s*[\d.]+(?:deg)?\s*,\s*[\d.]+%\s*,\s*([\d.]+)%", v)
    if hsl:
        return float(hsl.group(1)) < 25
    if not re.match(r"#[0-9a-fA-F]{3,8}\b|rgb", v):
        return False
    rgb = hex_to_rgb(v) if v.startswith("#") else tuple(int(x) for x in re.findall(r"\d+", v)[:3])
    return bool(rgb) and len(rgb) == 3 and sum(rgb) / 3 < 70


def fonts(html: str) -> list[str]:
    out = []
    for m in re.finditer(r"family=([A-Za-z+]+)", html):
        out.append(m.group(1).replace("+", " "))
    for m in re.finditer(r"font-family\s*:\s*['\"]?([A-Za-z][\w ]+?)['\",;]", html):
        name = m.group(1).strip()
        if name.lower() not in ("sans-serif", "serif", "monospace", "inherit", "system-ui", "arial", "helvetica", "var"):
            out.append(name)
    seen = []
    for f in out:
        if f not in seen:
            seen.append(f)
    return seen[:2]


def site_kind(text: str):
    for pat, names in SITE_KINDS:
        if re.search(pat, text, re.I):
            return names
    return ("un sito web", "a website")


def clean_desc(desc: str) -> str:
    desc = re.sub(r"https?://\S+", "", desc)
    desc = re.sub(r"[:#*_`>\[\]]|\(\s*\)", " ", desc)
    desc = re.sub(r"\s+", " ", desc).strip(" .-")
    if re.search(r"(frontend ?mentor|challenge|assignment|homework|exercise|clone)", desc, re.I):
        return ""
    return desc[:220]


def make_instruction(rng: random.Random, meta: dict, info: PageInfo, html: str) -> str:
    topics = " ".join(meta.get("topics") or [])
    blob = " ".join([meta.get("description") or "", topics, info.title, " ".join(info.h1)])
    kind_it, kind_en = site_kind(blob)
    italian = rng.random() < 0.7
    desc = clean_desc(meta.get("description") or info.meta_desc)
    title = re.split(r"\s+[-|–—·:]\s+", info.title)[0].strip() if info.title else ""
    if len(title) > 50 or re.search(r"document|untitled|index|template|theme|^home$|page$", title, re.I):
        title = ""
    sections = [h for h in info.h2 if 2 < len(h) < 40][:6]
    nav = [n for n in info.nav if 1 < len(n) < 20][:6]
    cols = palette(html)
    fnts = fonts(html)
    dark = is_dark(html)
    feats = [names for pat, names in FEATURES if re.search(pat, html, re.I)][:4]
    uses_tailwind = "cdn.tailwindcss.com" in html
    uses_bootstrap = "bootstrap" in html.lower()

    detail = rng.choice(["short", "medium", "long", "long"])
    parts = []
    if italian:
        verb = rng.choice(["Crea", "Realizza", "Genera", "Progetta", "Fammi", "Costruisci"])
        head = f"{verb} {kind_it}"
        if title:
            head += (" chiamata" if kind_it.startswith("una ") else " chiamato") + f" \"{title}\""
        parts.append(head + ".")
        if desc and detail != "short":
            parts.append(f"Descrizione: {desc}.")
        if detail in ("medium", "long"):
            if sections:
                parts.append("Sezioni: " + ", ".join(sections) + ".")
            elif nav:
                parts.append("Voci del menu: " + ", ".join(nav) + ".")
        if detail == "long":
            stile = "tema scuro" if dark else "tema chiaro"
            s = f"Stile pulito e moderno, {stile}"
            if cols:
                s += ", palette " + "/".join(cols)
            if fnts:
                s += ", font " + " e ".join(fnts)
            parts.append(s + ".")
            if feats:
                parts.append("Includi: " + ", ".join(f[0] for f in feats) + ".")
            if uses_tailwind:
                parts.append("Usa Tailwind CSS via CDN.")
            elif uses_bootstrap:
                parts.append("Usa Bootstrap 5.")
        if rng.random() < 0.5:
            parts.append(rng.choice([
                "Deve essere responsive.", "Tutto in un unico file HTML.",
                "Voglio un design minimal e curato.", "Ottimizzato per mobile.",
            ]))
    else:
        verb = rng.choice(["Create", "Build", "Make", "Design", "Generate"])
        head = f"{verb} {kind_en}"
        if title:
            head += f" called \"{title}\""
        parts.append(head + ".")
        if desc and detail != "short":
            parts.append(f"Description: {desc}.")
        if detail in ("medium", "long"):
            if sections:
                parts.append("Sections: " + ", ".join(sections) + ".")
            elif nav:
                parts.append("Menu items: " + ", ".join(nav) + ".")
        if detail == "long":
            s = "Clean, modern style, " + ("dark theme" if dark else "light theme")
            if cols:
                s += ", " + "/".join(IT2EN[c] for c in cols) + " palette"
            if fnts:
                s += ", " + " and ".join(fnts) + " font"
            parts.append(s + ".")
            if feats:
                parts.append("Include: " + ", ".join(f[1] for f in feats) + ".")
            if uses_tailwind:
                parts.append("Use Tailwind CSS from the CDN.")
            elif uses_bootstrap:
                parts.append("Use Bootstrap 5.")
        if rng.random() < 0.5:
            parts.append(rng.choice([
                "Make it responsive.", "Single HTML file please.",
                "Minimal and polished design.", "Mobile-first.",
            ]))
    return " ".join(parts)


# --------------------------------------------------------------------------- main

def html_pages(meta: dict) -> list[str]:
    pages = [f for f in meta.get("files", []) if f.lower().endswith((".html", ".htm"))]
    pages = [p for p in pages if not re.search(r"(404|test|demo/old|backup|copy)", p, re.I)]
    pages.sort(key=lambda p: (0 if p.lower().endswith("index.html") else 1, p.count("/"), len(p)))
    return pages


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="data/raw")
    ap.add_argument("--out", default="data/dataset")
    ap.add_argument("--pages-per-repo", type=int, default=3)
    ap.add_argument("--min-chars", type=int, default=1000)
    ap.add_argument("--max-chars", type=int, default=24000,
                    help="lunghezza massima della pagina finale (~3.5 caratteri per token)")
    ap.add_argument("--min-score", type=int, default=5)
    ap.add_argument("--variants", type=int, default=1, help="richieste diverse per la stessa pagina")
    ap.add_argument("--val-ratio", type=float, default=0.03)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    raw = Path(args.raw)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stats = Counter()
    seen_hashes = set()
    examples = []

    repos = sorted(p for p in raw.iterdir() if (p / "meta.json").exists())
    for repo in repos:
        meta = json.loads((repo / "meta.json").read_text("utf-8"))
        files_dir = repo / "files"
        taken = 0
        for page in html_pages(meta):
            if taken >= args.pages_per_repo:
                break
            stats["pages_seen"] += 1
            html = read_text(files_dir / page)
            if html is None or "<html" not in html.lower() and "<body" not in html.lower():
                stats["skip_not_full_html"] += 1
                continue
            try:
                html = inline_page(html, page, files_dir)
            except SkipPage as e:
                stats["skip_missing_css" if str(e).startswith("CSS mancante") else "skip_bundler"] += 1
                continue
            except Exception:
                stats["skip_error"] += 1
                continue
            html = clean(fix_assets(html))
            if re.search(r"\{\{|\{%|<\?php|<%=|\bv-for=|\bng-repeat", html):
                stats["skip_template_lang"] += 1
                continue
            if len(html) < args.min_chars:
                stats["skip_too_short"] += 1
                continue
            if len(html) > args.max_chars:
                stats["skip_too_long"] += 1
                continue
            info = PageInfo()
            try:
                info.feed(html)
            except Exception:
                stats["skip_parse"] += 1
                continue
            if info.text_len < 50:
                stats["skip_little_text"] += 1
                continue
            score, _ = quality_score(html)
            if score < args.min_score:
                stats["skip_quality"] += 1
                continue
            h = hashlib.sha1(re.sub(r"\s+", "", html).encode()).hexdigest()
            if h in seen_hashes:
                stats["skip_duplicate"] += 1
                continue
            seen_hashes.add(h)
            for _ in range(args.variants):
                prompt = make_instruction(rng, meta, info, html)
                examples.append({
                    "messages": [
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": prompt},
                        {"role": "assistant", "content": f"```html\n{html}```"},
                    ],
                    "source": meta.get("html_url", ""),
                    "license": meta.get("license", ""),
                    "page": page,
                    "score": score,
                })
            taken += 1
            stats["pages_kept"] += 1
        if taken:
            stats["repos_used"] += 1

    # split per repo (niente leakage tra train e val)
    by_src = {}
    for ex in examples:
        by_src.setdefault(ex["source"], []).append(ex)
    srcs = sorted(by_src)
    rng.shuffle(srcs)
    n_val = max(1, int(len(srcs) * args.val_ratio)) if len(srcs) > 10 else 0
    val_srcs = set(srcs[:n_val])
    train = [e for s in srcs if s not in val_srcs for e in by_src[s]]
    val = [e for s in val_srcs for e in by_src[s]]
    rng.shuffle(train)

    for name, rows in (("train.jsonl", train), ("val.jsonl", val)):
        with open(out / name, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    stats["train"] = len(train)
    stats["val"] = len(val)
    stats["repos_total"] = len(repos)
    (out / "stats.json").write_text(json.dumps(stats, indent=1), "utf-8")
    print(json.dumps(stats, indent=1))


if __name__ == "__main__":
    main()
