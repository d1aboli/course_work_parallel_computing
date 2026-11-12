#!/usr/bin/env python3
"""Prepare the search corpus from a Project Gutenberg dump.

Reads every *.txt from a zip archive or a directory, strips the Gutenberg
licence header/footer, normalises line endings and splits each book into
documents of roughly --chunk-kb kilobytes on paragraph boundaries.

Usage:
    python tools/prepare_dataset.py D1.7GB.zip data/docs
    python tools/prepare_dataset.py D1.7GB/ data/docs --chunk-kb 100 --english-only
"""

import argparse
import json
import os
import re
import statistics
import sys
import zipfile

START_RE = re.compile(rb"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\n]*\n")
END_RE = re.compile(rb"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG")
LANG_RE = re.compile(rb"^Language:\s*(.+?)\s*$", re.MULTILINE)
PARA_RE = re.compile(rb"\n\s*\n")


def iter_sources(src):
    """Yields (book_id, raw_bytes) in a deterministic (sorted) order."""
    if zipfile.is_zipfile(src):
        with zipfile.ZipFile(src) as zf:
            names = sorted(n for n in zf.namelist() if n.lower().endswith(".txt"))
            for name in names:
                yield os.path.splitext(os.path.basename(name))[0], zf.read(name)
    else:
        names = []
        for root, _, files in os.walk(src):
            names += [os.path.join(root, f) for f in files if f.lower().endswith(".txt")]
        for path in sorted(names):
            with open(path, "rb") as fh:
                yield os.path.splitext(os.path.basename(path))[0], fh.read()


def strip_gutenberg(raw):
    """Returns (body, stripped_flag)."""
    start = START_RE.search(raw)
    end = END_RE.search(raw)
    if start and end and end.start() > start.end():
        return raw[start.end():end.start()], True
    return raw, False


def hard_split(para, chunk_bytes):
    """Splits an oversized paragraph at the last newline or space before the limit."""
    while len(para) > chunk_bytes:
        cut = para.rfind(b"\n", 0, chunk_bytes)
        if cut < chunk_bytes // 2:
            cut = para.rfind(b" ", 0, chunk_bytes)
        if cut <= 0:
            cut = chunk_bytes
        yield para[:cut]
        para = para[cut:].lstrip()
    if para:
        yield para


def split_paragraphs(body, chunk_bytes):
    """Greedily packs paragraphs into chunks of about chunk_bytes."""
    chunks, current, size = [], [], 0
    paragraphs = (piece for p in PARA_RE.split(body) for piece in hard_split(p, chunk_bytes))
    for para in paragraphs:
        para = para.strip()
        if not para:
            continue
        if size and size + len(para) > chunk_bytes:
            chunks.append(b"\n\n".join(current))
            current, size = [], 0
        current.append(para)
        size += len(para) + 2
    if current:
        chunks.append(b"\n\n".join(current))
    # A tiny tail is merged into the previous chunk instead of being a document.
    if len(chunks) > 1 and len(chunks[-1]) < chunk_bytes // 4:
        tail = chunks.pop()
        chunks[-1] += b"\n\n" + tail
    return chunks


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("source", help="zip archive or directory with Gutenberg *.txt")
    ap.add_argument("out", help="output directory for documents")
    ap.add_argument("--chunk-kb", type=int, default=100)
    ap.add_argument("--english-only", action="store_true")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    chunk_bytes = args.chunk_kb * 1024
    sizes, books, stripped, skipped_lang = [], 0, 0, 0

    for book_id, raw in iter_sources(args.source):
        if args.english_only:
            lang = LANG_RE.search(raw[:20000])
            if lang and b"english" not in lang.group(1).lower():
                skipped_lang += 1
                continue
        body, was_stripped = strip_gutenberg(raw)
        stripped += was_stripped
        body = body.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        books += 1
        for part, chunk in enumerate(split_paragraphs(body, chunk_bytes)):
            name = f"{book_id}_{part:04d}.txt"
            with open(os.path.join(args.out, name), "wb") as fh:
                fh.write(chunk)
            sizes.append(len(chunk))
        if books % 200 == 0:
            print(f"  {books} books, {len(sizes)} documents", file=sys.stderr, flush=True)

    total = sum(sizes)
    manifest = {
        "source": os.path.basename(os.path.normpath(args.source)),
        "chunk_kb": args.chunk_kb,
        "english_only": args.english_only,
        "books": books,
        "books_with_licence_stripped": stripped,
        "books_skipped_by_language": skipped_lang,
        "documents": len(sizes),
        "total_bytes": total,
        "total_gb": round(total / 1e9, 3),
        "total_gib": round(total / 2**30, 3),
        "doc_bytes_min": min(sizes) if sizes else 0,
        "doc_bytes_median": int(statistics.median(sizes)) if sizes else 0,
        "doc_bytes_max": max(sizes) if sizes else 0,
    }
    with open(os.path.join(os.path.dirname(os.path.normpath(args.out)) or ".",
                           "manifest.json"), "w") as fh:
        json.dump(manifest, fh, indent=2)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
