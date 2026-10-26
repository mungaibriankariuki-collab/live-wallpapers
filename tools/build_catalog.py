"""Builds the 4K gallery: portrait featured pictures from Wikimedia Commons with free licences.

Categories come from the topic-sorted Commons:Featured pictures/... gallery pages, so every photo is
a community-picked featured picture on that topic.

Usage: python build_catalog.py OUT_DIR
Writes OUT_DIR/full/<id>.jpg and OUT_DIR/catalog.json (then run finalize.py to resize for phones).
"""
import json, os, random, re, sys, time, hashlib, urllib.parse, urllib.request

UA = "LiveWallpapersCatalog/1.0 (https://github.com/mungaibriankariuki-collab/live-wallpapers)"
API = "https://commons.wikimedia.org/w/api.php"
PER_CATEGORY = 10
FP = "Commons:Featured pictures/"

CATEGORIES = [
    ("Space", ["Astronomy"]),
    ("Landscapes", ["Places/Natural"]),
    ("Sky & Weather", ["Natural phenomena", "Natural phenomena/Weather"]),
    ("Birds", ["Animals/Birds"]),
    ("Butterflies", ["Animals/Arthropods/Lepidoptera", "Animals/Arthropods/Odonata"]),
    ("Wildlife", ["Animals/Mammals", "Animals/Reptiles", "Animals/Amphibians", "Animals/Mammals/Carnivora"]),
    ("Flowers", ["Plants", "Plants/Asparagales", "Plants/Rosales", "Plants/Ranunculales"]),
    ("Fungi", ["Fungi"]),
    ("Ceilings & Interiors", ["Places/Interiors/Religious buildings/Ceilings", "Places/Interiors"]),
    ("Architecture", ["Places/Architecture/Towers", "Places/Architecture/Religious buildings", "Places/Architecture/Exteriors"]),
    ("Cities", ["Places/Architecture/Cityscapes"]),
    ("Minerals", ["Objects/Rocks and minerals", "Objects/Rocks and Minerals"]),
]

OK_LICENSE = re.compile(r"^(Public domain|PD|CC0|CC BY(-SA)? [0-9.]+)", re.I)


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.load(r)
        except Exception as e:
            print("   api retry", attempt, e, flush=True)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("API failed")


def files_on(page):
    """Every file shown on a gallery page (including transcluded sub-galleries)."""
    titles, cont = [], {}
    while True:
        d = api({"action": "query", "prop": "images", "titles": FP + page, "imlimit": 500, **cont})
        for p in d.get("query", {}).get("pages", {}).values():
            titles += [i["title"] for i in p.get("images", [])]
        if "continue" not in d:
            return titles
        cont = d["continue"]


def info(titles, meta=False):
    out = {}
    for i in range(0, len(titles), 50):
        params = {"action": "query", "titles": "|".join(titles[i:i + 50]), "prop": "imageinfo",
                  "iiprop": "size|mime" + ("|url|extmetadata" if meta else "")}
        if meta:
            params.update({"iiurlwidth": 2160, "iiextmetadatafilter": "LicenseShortName|Artist|ObjectName"})
        d = api(params)
        for p in d.get("query", {}).get("pages", {}).values():
            if "imageinfo" in p:
                out[p["title"]] = p["imageinfo"][0]
    return out


def clean(html):
    text = re.sub(r"<[^>]+>", "", html or "")
    text = re.sub(r"label QS:.*$", "", text)
    return re.sub(r"\s+", " ", text).strip()[:80]


def fetch(url, path):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            with open(path, "wb") as f:
                f.write(data)
            return True
        except Exception as e:  # rate limits: back off and retry
            print("   retry", attempt, e, flush=True)
            time.sleep(8 * (attempt + 1))
    return False


def main(out):
    os.makedirs(f"{out}/full", exist_ok=True)
    rng = random.Random(7)
    used = set()
    catalog = {"version": 1, "categories": []}
    for name, pages in CATEGORIES:
        titles = []
        for page in pages:
            titles += [t for t in files_on(page) if t not in titles and t not in used]
        sizes = info(titles)
        portrait = [t for t, ii in sizes.items()
                    if ii.get("mime") == "image/jpeg" and ii["width"] >= 2160 and 1.35 <= ii["height"] / ii["width"] <= 2.4]
        rng.shuffle(portrait)
        print(f"== {name}: {len(titles)} files, {len(portrait)} portrait 4K", flush=True)
        details = info(portrait[:PER_CATEGORY * 3], meta=True)
        items = []
        for title in portrait[:PER_CATEGORY * 3]:
            if len(items) >= PER_CATEGORY:
                break
            ii = details.get(title)
            if not ii:
                continue
            meta = ii.get("extmetadata", {})
            lic = meta.get("LicenseShortName", {}).get("value", "")
            if not OK_LICENSE.match(lic):
                continue
            ident = hashlib.sha1(title.encode()).hexdigest()[:12]
            full = f"{out}/full/{ident}.jpg"
            if not (os.path.exists(full) and os.path.getsize(full) > 0) and not fetch(ii["thumburl"], full):
                continue
            used.add(title)
            label = clean(meta.get("ObjectName", {}).get("value", "")) or title[5:].rsplit(".", 1)[0]
            items.append({
                "id": ident,
                "title": label[:60],
                "author": clean(meta.get("Artist", {}).get("value", "")) or "Unknown",
                "license": lic,
                "source": ii.get("descriptionurl", ""),
            })
            print(f"   {lic:14} {label[:60]}", flush=True)
            time.sleep(1)
        catalog["categories"].append({"name": name, "items": items})
    with open(f"{out}/catalog.json", "w") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)
    print("total", sum(len(c["items"]) for c in catalog["categories"]))


if __name__ == "__main__":
    main(sys.argv[1])
