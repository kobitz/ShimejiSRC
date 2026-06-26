#!/usr/bin/env python3
"""
Mascot Memory Auditor
A health check + targeted pruner for the Shimeji AI fork's conversational state.

Audits (read-only):
  (A) chat.log convergence  — per-mascot dominant openers, repeated n-gram
      templates, and exact-duplicate lines. Surfaces the small-model failure mode
      (every character collapsing onto "your X means/proves Y when Z") early.
  (B) memory.json health    — per-mascot poisoning signatures: stale [Observed]
      facts, near-duplicate facts, malformed peer/emotional tones (colon/comma
      leakage), and repetitive peer/user exchange histories.

Prune (edits memory.json, opt-in, always backs up first):
  clear peerExchanges / userExchanges, drop [Observed] facts, dedup facts, reset
  malformed tones. chat.log is never modified (append-only diagnostic log).

  IMPORTANT: close Shimeji before pruning — a running instance holds memory in
  RAM and will overwrite on-disk edits on its next save.

Point it at the install folder (the one holding chat.log and img/). Stdlib only.
"""

import os
import re
import json
import shutil
import difflib
import datetime
import threading
import tkinter as tk
from tkinter import ttk, filedialog, scrolledtext, messagebox

# ─────────────────────────────────────────────
#  Defaults (tunable in the UI)
# ─────────────────────────────────────────────
DEFAULTS = {
    "opener_words": 3,     # how many leading words define an "opener"
    "opener_min":   3,     # flag an opener used at least this many times
    "ngram_n":      3,     # n-gram size for template detection
    "ngram_min":    4,     # flag an n-gram repeated at least this many times
    "observed_max": 6,     # flag a memory with at least this many [Observed] facts
    "dup_ratio":    0.85,  # difflib ratio at/above which two strings are "near-duplicate"
}

# Source parentheticals that mark a line as a MASCOT utterance (vs. User / raw audio source).
MASCOT_MARKERS = ("(to:", "(screen glance", "(say", "(peer", "(window")

# Tiny stopword set — only used to drop all-stopword n-grams from the template report.
STOPWORDS = set("a an the of to in on at for and or but is are was were be been being "
                "you your yours i me my we us our they them their it its this that these "
                "those with as by from not no do does did have has had will would can could "
                "may might so if then than there here what when who how".split())

WORD_RE = re.compile(r"[a-z0-9']+")

# Prune actions: key -> (info-count field, label)
PRUNE_ACTIONS = [
    ("peer",     "peer_pairs", "clear peerEx"),
    ("user",     "user_pairs", "clear userEx"),
    ("observed", "observed",   "drop [Observed]"),
    ("dedup",    "dups",       "dedup facts"),
    ("tones",    "bad_tones",  "fix tones"),
]


# ─────────────────────────────────────────────
#  Parsing
# ─────────────────────────────────────────────
def split_source_message(rest):
    """Split 'SOURCE:MESSAGE' where SOURCE may contain ':' inside parentheses
    (e.g. 'Hornet(to: 2B)'). Returns (source, message) on the first top-level ':'."""
    depth = 0
    for i, c in enumerate(rest):
        if c == "(":
            depth += 1
        elif c == ")":
            depth = max(0, depth - 1)
        elif c == ":" and depth == 0:
            return rest[:i], rest[i + 1:]
    return rest, ""


def parse_chat_log(path):
    """Return [(source, message)] for each well-formed line.
    Line format: yyyy-MM-dd:HH:mm:ss:SOURCE:MESSAGE  (timestamp = first 19 chars)."""
    out = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if len(line) < 21 or line[4] != "-" or line[10] != ":":
                continue  # not a timestamped line (continuation / blank)
            source, message = split_source_message(line[20:])
            out.append((source.strip(), message.strip()))
    return out


def speaker_of(source):
    return source.split("(", 1)[0].strip()


def is_mascot_line(source):
    return any(m in source for m in MASCOT_MARKERS)


def normalize(text):
    t = text.strip().strip('"').strip("'").strip()
    return re.sub(r"\s+", " ", t).lower()


def words(text):
    return WORD_RE.findall(text.lower())


# ─────────────────────────────────────────────
#  (A) chat.log convergence
# ─────────────────────────────────────────────
def chatlog_data(path):
    """Return (by_mascot {name: [messages]}, total_lines_parsed)."""
    by_mascot = {}
    n = 0
    for source, message in parse_chat_log(path):
        n += 1
        if message and is_mascot_line(source):
            name = speaker_of(source)
            if name:
                by_mascot.setdefault(name, []).append(message)
    return by_mascot, n


def chatlog_findings(path, cfg):
    """Structured dominant phrases per mascot, for the Repair tab's ban suggestions."""
    by_mascot, _ = chatlog_data(path)
    ow, n = cfg["opener_words"], cfg["ngram_n"]
    out = {}
    for name, msgs in by_mascot.items():
        total = len(msgs)
        openers, counts = {}, {}
        for m in msgs:
            ws = words(m)
            w = ws[:ow]
            if w:
                openers[" ".join(w)] = openers.get(" ".join(w), 0) + 1
            for i in range(len(ws) - n + 1):
                gram = ws[i:i + n]
                if all(g in STOPWORDS for g in gram):
                    continue
                key = " ".join(gram)
                counts[key] = counts.get(key, 0) + 1
        out[name] = {
            "total": total,
            "openers": [(o, c, 100.0 * c / total) for o, c in
                        sorted(openers.items(), key=lambda kv: -kv[1])[:6]],
            "ngrams": [(g, c) for g, c in sorted(counts.items(), key=lambda kv: -kv[1])[:6]],
        }
    return out


def analyze_chatlog(path, cfg):
    by_mascot, nlines = chatlog_data(path)
    flags = 0
    lines = [("h", "CHAT.LOG CONVERGENCE"),
             ("", f"Parsed {nlines} log lines; "
                  f"{sum(len(v) for v in by_mascot.values())} mascot utterances "
                  f"across {len(by_mascot)} characters.\n")]
    if not by_mascot:
        lines.append(("", "No mascot utterances found. Is this the install folder with chat.log?"))
        return lines, flags

    ow, omin, n, nmin = cfg["opener_words"], cfg["opener_min"], cfg["ngram_n"], cfg["ngram_min"]
    for name in sorted(by_mascot, key=lambda k: -len(by_mascot[k])):
        msgs = by_mascot[name]
        total = len(msgs)
        lines.append(("h2", f"{name}  ({total} lines)"))

        openers = {}
        for m in msgs:
            w = words(m)[:ow]
            if w:
                openers[" ".join(w)] = openers.get(" ".join(w), 0) + 1
        flagged = [(o, c) for o, c in sorted(openers.items(), key=lambda kv: -kv[1]) if c >= omin]
        if flagged:
            for o, c in flagged[:8]:
                flags += 1
                lines.append(("flag", f'  opener "{o}..."  x{c}  ({100.0 * c / total:.0f}% of lines)'))
        else:
            top = max(openers.items(), key=lambda kv: kv[1]) if openers else ("-", 0)
            lines.append(("", f'  openers varied (top "{top[0]}..." x{top[1]})'))

        counts = {}
        for m in msgs:
            ws = words(m)
            for i in range(len(ws) - n + 1):
                gram = ws[i:i + n]
                if all(g in STOPWORDS for g in gram):
                    continue
                key = " ".join(gram)
                counts[key] = counts.get(key, 0) + 1
        grams = [(g, c) for g, c in sorted(counts.items(), key=lambda kv: -kv[1]) if c >= nmin]
        if grams:
            for g, c in grams[:8]:
                flags += 1
                lines.append(("flag", f'  {n}-gram "{g}"  x{c}'))
        else:
            lines.append(("", f"  no {n}-gram repeated >={nmin}x (varied phrasing)"))

        seen = {}
        for m in msgs:
            k = normalize(m)
            seen[k] = seen.get(k, 0) + 1
        dups = sorted(((k, c) for k, c in seen.items() if c >= 2), key=lambda kv: -kv[1])
        for k, c in dups[:5]:
            flags += 1
            lines.append(("flag", f'  verbatim repeat x{c}: "{k[:80]}"'))

        lines.append(("", ""))
    return lines, flags


# ─────────────────────────────────────────────
#  (B) memory.json health
# ─────────────────────────────────────────────
def load_memory(path):
    # strict=False tolerates the literal control chars Java's writer can leave in strings.
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return json.loads(f.read(), strict=False)


def epoch_day_to_str(n):
    try:
        d = datetime.date(1970, 1, 1) + datetime.timedelta(days=int(n))
        return f"{d.isoformat()} ({(datetime.date.today() - d).days}d ago)"
    except Exception:
        return str(n)


def tone_malformed(tone):
    if not isinstance(tone, str) or not tone.strip():
        return False
    return (":" in tone) or ("," in tone) or (len(tone.split()) > 3)


def near_dup_groups(strings, ratio):
    """Group strings that are >= ratio similar (greedy). Returns [(rep, members)]."""
    groups = []
    used = [False] * len(strings)
    for i in range(len(strings)):
        if used[i]:
            continue
        members = [strings[i]]
        used[i] = True
        for j in range(i + 1, len(strings)):
            if not used[j] and difflib.SequenceMatcher(
                    None, strings[i].lower(), strings[j].lower()).ratio() >= ratio:
                members.append(strings[j])
                used[j] = True
        if len(members) > 1:
            groups.append((strings[i], members))
    return groups


def dedup_keep_first(strings, ratio):
    kept = []
    for s in strings:
        if not any(difflib.SequenceMatcher(None, s.lower(), k.lower()).ratio() >= ratio for k in kept):
            kept.append(s)
    return kept


def analyze_memory_file(path, cfg):
    flags = 0
    out = []
    name = os.path.basename(os.path.dirname(os.path.dirname(path)))
    try:
        data = load_memory(path)
    except Exception as e:
        out.append(("flag", f"{name}: could not parse memory.json ({e})"))
        return out, 1

    facts = [f for f in (data.get("facts") or []) if isinstance(f, str)]
    user_ex = data.get("userExchanges") or []
    peer_ex = data.get("peerExchanges") or []
    peer_tones = data.get("peerTones") or {}
    perm = data.get("permanentMemories") or []
    tone = data.get("emotionalTone", "")
    first_seen = data.get("firstSeenEpochDay", 0)

    out.append(("h2", f"{name}"))
    out.append(("", f"  interactions={data.get('interactionCount', 0)}  facts={len(facts)}  "
                    f"userEx={len(user_ex) // 2}  peerEx={len(peer_ex) // 2}  "
                    f"perm={len(perm)}  tone='{tone}'  "
                    f"firstSeen={epoch_day_to_str(first_seen) if first_seen else 'unset'}"))

    if tone_malformed(tone):
        flags += 1
        out.append(("flag", f'  emotionalTone looks malformed: "{tone}"'))
    for peer, t in peer_tones.items():
        if tone_malformed(t):
            flags += 1
            out.append(("flag", f'  peerTone[{peer}] looks malformed: "{t}"'))

    observed = [f for f in facts if f.startswith("[Observed]")]
    if len(observed) >= cfg["observed_max"]:
        flags += 1
        out.append(("flag", f"  {len(observed)} [Observed] facts - likely stale media; consider pruning"))
    for rep, members in near_dup_groups(facts, cfg["dup_ratio"]):
        flags += 1
        out.append(("flag", f'  {len(members)} near-duplicate facts: "{rep[:70]}"'))

    def mascot_texts(ex_list):
        return [e.get("text", "") for e in ex_list if e.get("role") == "mascot" and e.get("text")]

    for label, ex in (("peerExchanges", peer_ex), ("userExchanges", user_ex)):
        texts = mascot_texts(ex)
        if len(texts) < 3:
            continue
        openers = {}
        for t in texts:
            w = words(t)[:cfg["opener_words"]]
            if w:
                openers[" ".join(w)] = openers.get(" ".join(w), 0) + 1
        worst = max(openers.items(), key=lambda kv: kv[1]) if openers else ("", 0)
        share = worst[1] / len(texts)
        ndg = near_dup_groups(texts, cfg["dup_ratio"])
        if share >= 0.4 or ndg:
            flags += 1
            detail = []
            if share >= 0.4:
                detail.append(f'opener "{worst[0]}..." in {worst[1]}/{len(texts)}')
            if ndg:
                detail.append(f"{sum(len(m) for _, m in ndg)} near-dup replies")
            out.append(("flag", f"  {label} repetitive ({'; '.join(detail)}) - candidate for wipe to []"))

    return out, flags


def memory_prune_info(path, cfg):
    """Counts for the prune tab; None if unreadable."""
    try:
        data = load_memory(path)
    except Exception:
        return None
    facts = [f for f in (data.get("facts") or []) if isinstance(f, str)]
    observed = [f for f in facts if f.startswith("[Observed]")]
    dup_count = sum(len(m) - 1 for _, m in near_dup_groups(facts, cfg["dup_ratio"]))
    pt = data.get("peerTones") or {}
    bad = sum(1 for v in pt.values() if tone_malformed(v)) + \
        (1 if tone_malformed(data.get("emotionalTone", "")) else 0)
    return {
        "path": path,
        "name": os.path.basename(os.path.dirname(os.path.dirname(path))),
        "peer_pairs": len(data.get("peerExchanges") or []) // 2,
        "user_pairs": len(data.get("userExchanges") or []) // 2,
        "observed": len(observed),
        "dups": dup_count,
        "bad_tones": bad,
    }


def find_memory_files(install_dir):
    img = os.path.join(install_dir, "img")
    found = []
    if os.path.isdir(img):
        for d in sorted(os.listdir(img)):
            mj = os.path.join(img, d, "conf", "memory.json")
            if os.path.isfile(mj):
                found.append(mj)
    return found


def analyze_memory_dir(install_dir, cfg):
    lines = [("h", "MEMORY.JSON HEALTH")]
    total_flags = 0
    found = find_memory_files(install_dir)
    if not found:
        lines.append(("", "No img/*/conf/memory.json files found under the install folder."))
        return lines, 0
    lines.append(("", f"Scanned {len(found)} memory files.\n"))
    for mj in found:
        rep, flags = analyze_memory_file(mj, cfg)
        total_flags += flags
        lines.extend(rep)
        lines.append(("", ""))
    return lines, total_flags


# ─────────────────────────────────────────────
#  Pruning (edits memory.json — Java-compatible writer)
# ─────────────────────────────────────────────
def _jstr(s):
    """Escape a string exactly like MascotMemory.jsonStr (so Java reads it back identically)."""
    if s is None:
        return '""'
    s = str(s).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "")
    return '"' + s + '"'


def dump_memory(data):
    """Serialize back in MascotMemory.toJson's shape (its hand-rolled parser is format-tolerant)."""
    facts = [f for f in (data.get("facts") or []) if isinstance(f, str)]
    user_ex = data.get("userExchanges") or []
    peer_ex = data.get("peerExchanges") or []
    peer_tones = data.get("peerTones") or {}
    perm = data.get("permanentMemories") or []

    def ex_obj(e):
        if "speaker" in e:
            return '{"speaker":%s,"text":%s}' % (_jstr(e.get("speaker", "")), _jstr(e.get("text", "")))
        return '{"role":%s,"text":%s}' % (_jstr(e.get("role", "mascot")), _jstr(e.get("text", "")))

    def arr(items):
        return ("\n    " + ",\n    ".join(items) + "\n  ") if items else ""

    pt = arr([f"{_jstr(k)}: {_jstr(v)}" for k, v in peer_tones.items()])
    pm = arr(['{"keywords":[%s],"content":%s}'
              % (",".join(_jstr(k) for k in (p.get("keywords") or [])), _jstr(p.get("content", "")))
              for p in perm])
    return (
        "{\n"
        '  "interactionCount": %d,\n' % int(data.get("interactionCount", 0)) +
        '  "sinceLastSummary": %d,\n' % int(data.get("sinceLastSummary", 0)) +
        '  "firstSeenEpochDay": %d,\n' % int(data.get("firstSeenEpochDay", 0)) +
        '  "emotionalTone": %s,\n' % _jstr(data.get("emotionalTone", "neutral")) +
        '  "peerTones": {%s},\n' % pt +
        '  "facts": [%s],\n' % arr([_jstr(f) for f in facts]) +
        '  "userExchanges": [%s],\n' % arr([ex_obj(e) for e in user_ex]) +
        '  "peerExchanges": [%s],\n' % arr([ex_obj(e) for e in peer_ex]) +
        '  "permanentMemories": [%s]\n' % pm +
        "}"
    )


def prune_memory(path, actions, cfg, backup=True):
    """Apply selected prune actions to one memory.json. Returns a short change summary."""
    data = load_memory(path)
    changes = []
    facts = [f for f in (data.get("facts") or []) if isinstance(f, str)]
    n0 = len(facts)
    if "observed" in actions:
        facts = [f for f in facts if not f.startswith("[Observed]")]
    if "dedup" in actions:
        facts = dedup_keep_first(facts, cfg["dup_ratio"])
    if len(facts) != n0:
        changes.append(f"facts {n0}->{len(facts)}")
    data["facts"] = facts

    if "peer" in actions and data.get("peerExchanges"):
        changes.append(f"peerEx cleared ({len(data['peerExchanges']) // 2})")
        data["peerExchanges"] = []
    if "user" in actions and data.get("userExchanges"):
        changes.append(f"userEx cleared ({len(data['userExchanges']) // 2})")
        data["userExchanges"] = []
    if "tones" in actions:
        if tone_malformed(data.get("emotionalTone", "")):
            data["emotionalTone"] = "neutral"
            changes.append("emotionalTone reset")
        pt = data.get("peerTones") or {}
        kept = {k: v for k, v in pt.items() if not tone_malformed(v)}
        if len(kept) != len(pt):
            changes.append(f"peerTones {len(pt)}->{len(kept)}")
        data["peerTones"] = kept

    if not changes:
        return None
    if backup:
        ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        shutil.copy2(path, f"{path}.{ts}.bak")
    with open(path, "w", encoding="utf-8") as f:
        f.write(dump_memory(data))
    return ", ".join(changes)


# ─────────────────────────────────────────────
#  Persona repair (edits <SpeechRule>/<Personality> in the mascot's Information XML)
# ─────────────────────────────────────────────
INFO_FILES = ("behaviors.xml", "actions.xml")


def _xml_unescape(s):
    return (s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
             .replace("&apos;", "'").replace("&amp;", "&"))


def _xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _get_el(text, tag):
    m = re.search(r"<%s>(.*?)</%s>" % (tag, tag), text, re.S)
    return _xml_unescape(m.group(1).strip()) if m else None


def find_information_file(img_dir):
    """The conf file holding this image set's <Information> block, or None."""
    for fn in INFO_FILES:
        p = os.path.join(img_dir, "conf", fn)
        if os.path.isfile(p):
            try:
                t = open(p, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            if "<Information>" in t and ("<Personality>" in t or "<SpeechRule>" in t or "<Name>" in t):
                return p
    return None


def scan_personas(install_dir):
    img = os.path.join(install_dir, "img")
    out = []
    if not os.path.isdir(img):
        return out
    for d in sorted(os.listdir(img)):
        img_dir = os.path.join(img, d)
        if not os.path.isdir(img_dir):
            continue
        f = find_information_file(img_dir)
        if not f:
            continue
        t = open(f, encoding="utf-8", errors="replace").read()
        out.append({"dir": d, "name": _get_el(t, "Name") or d, "file": f,
                    "personality": _get_el(t, "Personality") or "",
                    "speechrule": _get_el(t, "SpeechRule") or ""})
    return out


def set_element(text, tag, new_inner):
    """Replace <tag>...</tag> inner text, or insert the element into <Information>."""
    esc = _xml_escape(new_inner)
    pat = re.compile(r"(<%s>)(.*?)(</%s>)" % (tag, tag), re.S)
    if pat.search(text):
        return pat.sub(lambda m: m.group(1) + esc + m.group(3), text, count=1)
    ins = "\n\t\t<%s>%s</%s>" % (tag, esc, tag)
    if re.search(r"</Name>", text):
        return re.sub(r"(</Name>)", lambda m: m.group(1) + ins, text, count=1)
    return re.sub(r"(</Information>)", lambda m: ins + "\n\t" + m.group(1), text, count=1)


def save_persona(file, speechrule, personality, backup=True):
    """Write SpeechRule/Personality back into the Information XML. Returns what changed, or None."""
    t = open(file, encoding="utf-8", errors="replace").read()
    changed = []
    if speechrule.strip() != (_get_el(t, "SpeechRule") or "").strip():
        t = set_element(t, "SpeechRule", speechrule.strip())
        changed.append("SpeechRule")
    if personality.strip() != (_get_el(t, "Personality") or "").strip():
        t = set_element(t, "Personality", personality.strip())
        changed.append("Personality")
    if not changed:
        return None
    if backup:
        ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        shutil.copy2(file, f"{file}.{ts}.bak")
    with open(file, "w", encoding="utf-8") as f:
        f.write(t)
    return ", ".join(changed)


def suggest_ban(findings_for_name):
    """An instruction-style ban clause for the mascot's top fallbacks. Deliberately NOT a
    quotable 'RIGHT' sentence (those become the next crutch — the documented whack-a-mole)."""
    if not findings_for_name:
        return ""
    phrases = [o for o, c, _ in findings_for_name.get("openers", [])[:2]]
    for g, _ in findings_for_name.get("ngrams", [])[:2]:
        if g not in phrases:
            phrases.append(g)
    phrases = phrases[:3]
    if not phrases:
        return ""
    quoted = "; ".join('"%s"' % p for p in phrases)
    return ("Avoid these phrasings you have been over-relying on lately: %s. Do not open with them "
            "or fall back on them — say the specific thing you mean instead." % quoted)


# ─────────────────────────────────────────────
#  Auto-detect the install folder
# ─────────────────────────────────────────────
def looks_like_install(d):
    return d and os.path.isfile(os.path.join(d, "chat.log")) and os.path.isdir(os.path.join(d, "img"))


def guess_install_dir():
    for cand in (os.path.dirname(os.path.abspath(__file__)), os.getcwd()):
        if looks_like_install(cand):
            return cand
    return ""


# ─────────────────────────────────────────────
#  GUI
# ─────────────────────────────────────────────
class App:
    def __init__(self, root):
        self.root = root
        root.title("Mascot Memory Auditor")
        root.geometry("1000x760")
        self.infos = []  # prune infos from the last audit

        top = ttk.Frame(root, padding=8)
        top.pack(fill="x")
        ttk.Label(top, text="Install folder:").pack(side="left")
        self.path_var = tk.StringVar(value=guess_install_dir())
        ttk.Entry(top, textvariable=self.path_var).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(top, text="Browse...", command=self.browse).pack(side="left")
        ttk.Button(top, text="Run audit", command=self.run).pack(side="left", padx=6)

        cfgf = ttk.Frame(root, padding=(8, 0))
        cfgf.pack(fill="x")
        self.vars = {}
        for key, label in (("opener_min", "opener flag >="),
                           ("ngram_min", "n-gram flag >="),
                           ("observed_max", "[Observed] flag >=")):
            ttk.Label(cfgf, text=label).pack(side="left", padx=(8, 2))
            v = tk.IntVar(value=DEFAULTS[key])
            self.vars[key] = v
            ttk.Spinbox(cfgf, from_=1, to=50, width=4, textvariable=v).pack(side="left")

        self.nb = ttk.Notebook(root)
        self.nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.tabs = {}
        for key, title in (("summary", "Summary"),
                           ("chat", "chat.log convergence"),
                           ("memory", "memory.json health")):
            txt = scrolledtext.ScrolledText(self.nb, wrap="word", font=("Consolas", 10))
            txt.tag_config("h", font=("Consolas", 12, "bold"), foreground="#1a4f8a", spacing3=4)
            txt.tag_config("h2", font=("Consolas", 10, "bold"), foreground="#333", spacing1=4)
            txt.tag_config("flag", foreground="#b00000")
            txt.configure(state="disabled")
            self.nb.add(txt, text=title)
            self.tabs[key] = txt

        self._build_prune_tab()
        self._build_repair_tab()

        self.personas = []     # from scan_personas, last audit
        self.findings = {}     # from chatlog_findings, last audit

        self.status = tk.StringVar(value="Ready." if self.path_var.get()
                                   else "Pick the install folder (the one with chat.log + img/).")
        ttk.Label(root, textvariable=self.status, anchor="w", relief="sunken",
                  padding=4).pack(fill="x", side="bottom")

    # ── Prune tab ──
    def _build_prune_tab(self):
        outer = ttk.Frame(self.nb)
        self.nb.add(outer, text="Prune (edits memory.json)")

        warn = tk.Label(outer, fg="#b00000", justify="left", anchor="w", padx=8, pady=6,
                        text="WARNING: close Shimeji before pruning — a running instance holds memory in RAM "
                             "and will overwrite on-disk edits on its next save.\n"
                             "Each prune writes a timestamped .bak next to the file. chat.log is never modified.")
        warn.pack(fill="x")

        bar = ttk.Frame(outer, padding=(8, 2))
        bar.pack(fill="x")
        self.backup_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="Back up (.bak) before pruning", variable=self.backup_var).pack(side="left")
        ttk.Button(bar, text="Apply selected prunes", command=self.apply_prunes).pack(side="left", padx=8)
        ttk.Button(bar, text="Select all flagged", command=self.select_flagged).pack(side="left")
        ttk.Button(bar, text="Clear selection", command=self.clear_selection).pack(side="left", padx=6)

        head = ttk.Frame(outer, padding=(8, 4))
        head.pack(fill="x")
        ttk.Label(head, text="(run an audit to populate)", foreground="#666").pack(side="left")

        self.prune_rows = ttk.Frame(outer, padding=8)
        self.prune_rows.pack(fill="both", expand=True)
        self.prune_vars = {}   # (path, action) -> BooleanVar

    def _populate_prune(self, infos):
        for w in self.prune_rows.winfo_children():
            w.destroy()
        self.prune_vars = {}
        if not infos:
            ttk.Label(self.prune_rows, text="No memory.json files found.").grid(row=0, column=0, sticky="w")
            return
        # header
        ttk.Label(self.prune_rows, text="mascot", font=("Consolas", 9, "bold")).grid(row=0, column=0, sticky="w", padx=4)
        for c, (_, _, label) in enumerate(PRUNE_ACTIONS, start=1):
            ttk.Label(self.prune_rows, text=label, font=("Consolas", 9, "bold")).grid(row=0, column=c, padx=6)
        for r, info in enumerate(infos, start=1):
            ttk.Label(self.prune_rows, text=info["name"], font=("Consolas", 10)).grid(row=r, column=0, sticky="w", padx=4)
            for c, (action, field, _) in enumerate(PRUNE_ACTIONS, start=1):
                count = info[field]
                var = tk.BooleanVar(value=False)
                self.prune_vars[(info["path"], action)] = var
                cb = ttk.Checkbutton(self.prune_rows, variable=var,
                                     text=str(count) if count else "-")
                if not count:
                    cb.state(["disabled"])
                cb.grid(row=r, column=c, padx=6, pady=1)

    def select_flagged(self):
        # tick every action that has something to do (count > 0 => enabled)
        for (path, action), var in self.prune_vars.items():
            info = next((i for i in self.infos if i["path"] == path), None)
            field = dict((a, f) for a, f, _ in PRUNE_ACTIONS)[action]
            if info and info[field]:
                var.set(True)

    def clear_selection(self):
        for var in self.prune_vars.values():
            var.set(False)

    def apply_prunes(self):
        selected = {}
        for (path, action), var in self.prune_vars.items():
            if var.get():
                selected.setdefault(path, set()).add(action)
        if not selected:
            self.status.set("Nothing selected to prune.")
            return
        names = ", ".join(sorted(os.path.basename(os.path.dirname(os.path.dirname(p))) for p in selected))
        if not messagebox.askyesno(
                "Confirm prune",
                f"Apply the selected prunes to {len(selected)} memory file(s)?\n\n{names}\n\n"
                f"{'A timestamped .bak will be written first.' if self.backup_var.get() else 'NO backup will be written.'}\n\n"
                "Make sure Shimeji is closed, or it will overwrite these edits."):
            return
        cfg = self.cfg()
        backup = self.backup_var.get()
        results = []
        for path, actions in selected.items():
            name = os.path.basename(os.path.dirname(os.path.dirname(path)))
            try:
                summary = prune_memory(path, actions, cfg, backup)
                results.append(f"{name}: {summary}" if summary else f"{name}: (no change)")
            except Exception as e:
                results.append(f"{name}: ERROR {e}")
        messagebox.showinfo("Prune complete", "\n".join(results))
        self.status.set(f"Pruned {len(selected)} file(s). Re-auditing...")
        self.run()  # refresh the audit + prune counts

    # ── Repair tab (edits persona XML) ──
    def _build_repair_tab(self):
        outer = ttk.Frame(self.nb)
        self.nb.add(outer, text="Repair (edits personality XML)")

        tk.Label(outer, fg="#b00000", justify="left", anchor="w", padx=8, pady=6,
                 text="Edits img/<name>/conf <SpeechRule>/<Personality>; writes a .bak first. "
                      "Restart Shimeji (or reload images) to apply.\n"
                      "NOTE: banning a phrase often just shifts the model to the NEXT fallback — review the "
                      "wording, and keep guidance as instructions, not quotable example sentences."
                 ).pack(fill="x")

        row = ttk.Frame(outer, padding=(8, 2))
        row.pack(fill="x")
        ttk.Label(row, text="Mascot:").pack(side="left")
        self.repair_sel = tk.StringVar()
        self.repair_combo = ttk.Combobox(row, textvariable=self.repair_sel, state="readonly", width=24)
        self.repair_combo.pack(side="left", padx=6)
        self.repair_combo.bind("<<ComboboxSelected>>", lambda e: self._load_persona())
        ttk.Button(row, text="Insert suggested ban", command=self._insert_ban).pack(side="left", padx=6)
        ttk.Button(row, text="Reload from disk", command=self._load_persona).pack(side="left")
        ttk.Button(row, text="Save to XML", command=self._save_persona).pack(side="left", padx=6)

        ttk.Label(outer, text="Detected fallbacks (from last audit):").pack(anchor="w", padx=8, pady=(6, 0))
        self.repair_find = scrolledtext.ScrolledText(outer, height=4, wrap="word", font=("Consolas", 9))
        self.repair_find.configure(state="disabled")
        self.repair_find.pack(fill="x", padx=8)

        ttk.Label(outer, text="SpeechRule:").pack(anchor="w", padx=8, pady=(6, 0))
        self.repair_sr = scrolledtext.ScrolledText(outer, height=8, wrap="word", font=("Consolas", 10))
        self.repair_sr.pack(fill="both", padx=8)

        ttk.Label(outer, text="Personality:").pack(anchor="w", padx=8, pady=(6, 0))
        self.repair_pers = scrolledtext.ScrolledText(outer, height=12, wrap="word", font=("Consolas", 10))
        self.repair_pers.pack(fill="both", expand=True, padx=8, pady=(0, 8))

    def _refresh_repair(self, personas, findings):
        self.personas, self.findings = personas, findings
        names = [p["name"] for p in personas]
        self.repair_combo["values"] = names
        if names and self.repair_sel.get() not in names:
            self.repair_sel.set(names[0])
        if names:
            self._load_persona()

    def _current_persona(self):
        return next((p for p in self.personas if p["name"] == self.repair_sel.get()), None)

    def _load_persona(self):
        p = self._current_persona()
        if not p:
            return
        try:  # always read fresh from disk
            t = open(p["file"], encoding="utf-8", errors="replace").read()
            p["speechrule"] = _get_el(t, "SpeechRule") or ""
            p["personality"] = _get_el(t, "Personality") or ""
        except Exception:
            pass
        self.repair_sr.delete("1.0", "end")
        self.repair_sr.insert("end", p["speechrule"])
        self.repair_pers.delete("1.0", "end")
        self.repair_pers.insert("end", p["personality"])

        f = self.findings.get(p["name"])
        self.repair_find.configure(state="normal")
        self.repair_find.delete("1.0", "end")
        if f:
            self.repair_find.insert("end", "openers: " + ", ".join(
                '"%s" x%d' % (o, c) for o, c, _ in f["openers"][:5]) + "\n")
            self.repair_find.insert("end", "%d-grams: " % self.cfg()["ngram_n"] + ", ".join(
                '"%s" x%d' % (g, c) for g, c in f["ngrams"][:5]))
        else:
            self.repair_find.insert("end", "(run an audit to see this mascot's dominant phrases)")
        self.repair_find.configure(state="disabled")

    def _insert_ban(self):
        p = self._current_persona()
        if not p:
            return
        clause = suggest_ban(self.findings.get(p["name"]))
        if not clause:
            self.status.set("No dominant phrases detected for this mascot — run an audit first.")
            return
        cur = self.repair_sr.get("1.0", "end").strip()
        self.repair_sr.delete("1.0", "end")
        self.repair_sr.insert("end", (cur + "\n" if cur else "") + clause)
        self.status.set("Suggested ban inserted — review/edit the wording before saving.")

    def _save_persona(self):
        p = self._current_persona()
        if not p:
            return
        sr = self.repair_sr.get("1.0", "end").strip()
        pers = self.repair_pers.get("1.0", "end").strip()
        if not messagebox.askyesno(
                "Confirm save",
                f"Write SpeechRule/Personality changes to:\n{p['file']}\n\n"
                "A timestamped .bak will be written first. Restart Shimeji to apply.\n\n"
                "Close Shimeji first if it's open (it won't overwrite XML, but it won't pick up "
                "changes until reload)."):
            return
        try:
            changed = save_persona(p["file"], sr, pers, backup=True)
        except Exception as e:
            messagebox.showerror("Save failed", str(e))
            return
        if changed:
            messagebox.showinfo("Saved", f"Updated: {changed}\nRestart Shimeji (or reload images) to apply.")
            self.status.set(f"Saved {changed} for {p['name']}. Restart Shimeji to apply.")
        else:
            self.status.set("No changes to save.")

    # ── shared ──
    def browse(self):
        d = filedialog.askdirectory(title="Select the Shimeji install folder")
        if d:
            self.path_var.set(d)
            self.status.set("Install folder set." if looks_like_install(d)
                            else "Warning: chat.log/img not found here — audit may be empty.")

    def cfg(self):
        c = dict(DEFAULTS)
        for k, v in self.vars.items():
            try:
                c[k] = int(v.get())
            except Exception:
                pass
        return c

    def write(self, key, lines):
        t = self.tabs[key]
        t.configure(state="normal")
        t.delete("1.0", "end")
        for tag, text in lines:
            t.insert("end", text + "\n", tag if tag else ())
        t.configure(state="disabled")

    def run(self):
        d = self.path_var.get().strip()
        if not d or not os.path.isdir(d):
            self.status.set("Pick a valid install folder first.")
            return
        self.status.set("Auditing...")
        threading.Thread(target=self._run, args=(d, self.cfg()), daemon=True).start()

    def _run(self, d, cfg):
        try:
            chat_path = os.path.join(d, "chat.log")
            chat_lines, chat_flags = (analyze_chatlog(chat_path, cfg)
                                      if os.path.isfile(chat_path)
                                      else ([("", "No chat.log in this folder.")], 0))
            mem_lines, mem_flags = analyze_memory_dir(d, cfg)
            infos = [i for i in (memory_prune_info(p, cfg) for p in find_memory_files(d)) if i]
            findings = chatlog_findings(chat_path, cfg) if os.path.isfile(chat_path) else {}
            personas = scan_personas(d)

            summary = [("h", "AUDIT SUMMARY"), ("", f"Folder: {d}\n"),
                       ("flag" if chat_flags else "", f"chat.log convergence flags: {chat_flags}"),
                       ("flag" if mem_flags else "", f"memory.json health flags:   {mem_flags}"),
                       ("", "")]
            if chat_flags or mem_flags:
                summary.append(("", "See the tabs for detail. Findings are read-only; use the Prune tab to act "
                                    "on memory.json (close Shimeji first). chat.log is never modified."))
            else:
                summary.append(("", "Clean — no convergence or corruption flags at current thresholds."))

            def apply():
                self.infos = infos
                self.write("summary", summary)
                self.write("chat", chat_lines)
                self.write("memory", mem_lines)
                self._populate_prune(infos)
                self._refresh_repair(personas, findings)
                self.status.set(f"Done — {chat_flags + mem_flags} flag(s). "
                                "Audit is read-only; Prune/Repair tabs are opt-in.")
            self.root.after(0, apply)
        except Exception as e:
            self.root.after(0, lambda: self.status.set(f"Error: {e}"))


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
