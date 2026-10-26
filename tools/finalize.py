"""Resize gallery photos to phone 4K, tidy titles, write release-ready flat files and review sheets.

Usage: python finalize.py GALLERY_DIR OUT_DIR SHEETS_DIR [skip_id ...]
"""
import json, os, re, sys
from PIL import Image

src, out, sheets = sys.argv[1:4]
skip = set(sys.argv[4:])
os.makedirs(out, exist_ok=True)
os.makedirs(sheets, exist_ok=True)

cat = json.load(open(f"{src}/catalog.json"))


def tidy(title):
    title = re.sub(r"label QS:.*$", "", title)  # Wikidata label junk
    title = re.sub(r"\s+", " ", title).strip(" ,;-")
    return title[:60] or "Untitled"


for c in cat["categories"]:
    kept = []
    for it in c["items"]:
        full = f"{src}/full/{it['id']}.jpg"
        if it["id"] in skip or not os.path.exists(full) or os.path.getsize(full) == 0:
            continue
        with Image.open(full) as im:
            im = im.convert("RGB")
            w, h = im.size
            scale = min(2160 / w, 4096 / h, 1.0)
            if scale < 1.0:
                im = im.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
            dst = f"{out}/full_{it['id']}.jpg"
            im.save(dst, quality=88, optimize=True, progressive=True)
            it["width"], it["height"] = im.size
            it["bytes"] = os.path.getsize(dst)
            t = im.copy()
            t.thumbnail((360, 800))
            t.save(f"{out}/thumb_{it['id']}.jpg", quality=80, optimize=True)
        it["title"] = tidy(it["title"])
        kept.append(it)
    c["items"] = kept

    # A contact sheet per category, for eyeballing what's in it.
    tiles = [Image.open(f"{out}/thumb_{it['id']}.jpg") for it in kept]
    if tiles:
        cols = 5
        tw, th = 180, 320
        sheet = Image.new("RGB", (cols * tw, ((len(tiles) + cols - 1) // cols) * th), "black")
        for i, t in enumerate(tiles):
            t = t.resize((tw, th))
            sheet.paste(t, ((i % cols) * tw, (i // cols) * th))
        sheet.save(f"{sheets}/{c['name']}.jpg", quality=80)

cat["categories"] = [c for c in cat["categories"] if c["items"]]
json.dump(cat, open(f"{out}/catalog.json", "w"), ensure_ascii=False, indent=1)
total = sum(len(c["items"]) for c in cat["categories"])
size = sum(it["bytes"] for c in cat["categories"] for it in c["items"])
print("items", total, "MB", round(size / 1e6))
for c in cat["categories"]:
    print(c["name"], [(it["id"], it["title"][:30]) for it in c["items"]])
