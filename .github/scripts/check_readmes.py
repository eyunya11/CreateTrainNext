#!/usr/bin/env python3
"""Checks that README.md and README.ja.md agree with each other and with the source.

The two files say the same things in two languages, and quote numbers, names and
defaults that live in the code. Nothing else notices when one of them drifts.

    python3 .github/scripts/check_readmes.py [repository root]

Exits 1 and lists every problem it found, or exits 0 and says nothing is wrong.
Standard library only.
"""
import math
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
JAVA = ROOT / "src/main/java/com/eyunya/createtrainnext"
READMES = {"en": ROOT / "README.md", "ja": ROOT / "README.ja.md"}

problems = []


def fail(message):
    problems.append(message)


def read(path):
    return Path(path).read_text(encoding="utf-8")


# ---------------------------------------------------------------- reading a README

def outside_code(text):
    lines, fenced, fences = [], False, 0
    for line in text.splitlines():
        if line.startswith("```"):
            fenced = not fenced
            fences += 1
            continue
        if not fenced:
            lines.append(line)
    return lines, fences


def cells(row):
    """The cells of one table row, not splitting on a | inside backticks."""
    parts, current, in_code = [], "", False
    for ch in row.strip():
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            parts.append(current)
            current = ""
        else:
            current += ch
    parts.append(current)
    return [p.strip() for p in parts[1:-1]]


def structure(text):
    lines, fences = outside_code(text)
    headings = [len(m.group(1)) for m in (re.match(r"^(#{1,6}) ", l) for l in lines) if m]
    tables, current = [], []
    for line in lines:
        if line.startswith("|"):
            current.append(cells(line))
        elif current:
            tables.append(current)
            current = []
    if current:
        tables.append(current)
    return headings, tables, fences


def all_rows(tables):
    return [row for table in tables for row in table]


# ---------------------------------------------------------------- the two files agree

texts, tables_of = {}, {}
for lang, path in READMES.items():
    if not path.exists():
        fail("%s is missing" % path)
        continue
    texts[lang] = read(path)

if len(texts) == 2:
    h_en, t_en, f_en = structure(texts["en"])
    h_ja, t_ja, f_ja = structure(texts["ja"])
    tables_of = {"en": t_en, "ja": t_ja}
    if h_en != h_ja:
        fail("the headings differ in number or level (en %d, ja %d)" % (len(h_en), len(h_ja)))
    if f_en != f_ja:
        fail("the number of code blocks differs (en %d, ja %d)" % (f_en // 2, f_ja // 2))
    if len(t_en) != len(t_ja):
        fail("the number of tables differs (en %d, ja %d)" % (len(t_en), len(t_ja)))
    else:
        for i, (a, b) in enumerate(zip(t_en, t_ja), 1):
            if len(a) != len(b):
                fail("table %d has %d rows in English and %d in Japanese (starts: %s | %s)"
                     % (i, len(a), len(b), a[0][0], b[0][0]))
            for lang, table in (("en", a), ("ja", b)):
                widths = {len(r) for r in table}
                if len(widths) != 1:
                    fail("table %d (%s) has rows with different numbers of columns" % (i, lang))

for lang, text in texts.items():
    for target in re.findall(r"\]\(([^)#\s]+)[^)]*\)", text):
        if not re.match(r"[a-z]+:", target) and not (ROOT / target).exists():
            fail("%s links to %s, which does not exist" % (READMES[lang].name, target))

# ---------------------------------------------------------------- the names agree with the source

def source_names(pattern, text):
    return set(re.findall(pattern, text))


commands = source_names(r'Commands\.literal\("(\w+)"\)', read(JAVA / "CTNCommands.java"))
registry = read(JAVA / "CTNRegistry.java")
items = source_names(r'register(?:SimpleBlockItem|Item)\(\s*"(\w+)"', registry)


def config_entries():
    """(section.key, default, [numbers in the range]) for every setting in CTNConfig."""
    entries, section = {}, []
    token = re.compile(
        r'\.push\("(\w+)"\)|builder\.pop\(\)'
        r'|\.defineInRange\("(\w+)",\s*([^,]+),\s*([^,]+),\s*([^)]+)\)'
        r'|\.define\("(\w+)",\s*([^)]+)\)')
    for m in token.finditer(read(JAVA / "CTNConfig.java")):
        if m.group(1):
            section.append(m.group(1))
        elif m.group(0) == "builder.pop()":
            section.pop()
        elif m.group(2):
            entries[".".join(section + [m.group(2)])] = (m.group(3).strip(), [m.group(4).strip(), m.group(5).strip()])
        else:
            entries[".".join(section + [m.group(6)])] = (m.group(7).strip(), None)
    return entries


def same_number(a, b):
    try:
        return math.isclose(float(a), float(b))
    except ValueError:
        return str(a).lower() == str(b).lower()


config = config_entries()

for lang, tables in tables_of.items():
    name = READMES[lang].name
    rows = all_rows(tables)

    listed = {r[0].strip("`").split()[0] for r in rows if r and re.match(r"^`/?[a-z]+( |`)", r[0])
              and r[0].strip("`").split()[0] in commands | {"resetgraph"}}
    if listed != commands:
        fail("%s: commands differ from CTNCommands (README only: %s; source only: %s)"
             % (name, sorted(listed - commands), sorted(commands - listed)))

    ids = {m for r in rows for m in re.findall(r"`createtrainnext:(\w+)`", r[2] if len(r) > 2 else "")}
    if ids != items:
        fail("%s: item IDs differ from CTNRegistry (README only: %s; source only: %s)"
             % (name, sorted(ids - items), sorted(items - ids)))

    documented = {}
    for r in rows:
        if len(r) >= 3 and re.fullmatch(r"`[a-z_]+\.\w+`", r[0]) and r[0].strip("`") in config:
            documented[r[0].strip("`")] = r
    if set(documented) != set(config):
        fail("%s: settings differ from CTNConfig (README only: %s; source only: %s)"
             % (name, sorted(set(documented) - set(config)), sorted(set(config) - set(documented))))
    for key, r in documented.items():
        default, bounds = config[key]
        if not same_number(r[1], default):
            fail("%s: %s defaults to %s in the code but %s here" % (name, key, default, r[1]))
        if bounds:
            numbers = re.findall(r"-?\d+(?:\.\d+)?", r[2])
            if len(numbers) != 2 or not all(same_number(a, b) for a, b in zip(numbers, bounds)):
                fail("%s: %s ranges over %s in the code but %s here" % (name, key, bounds, r[2]))

# ---------------------------------------------------------------- the numbers agree with the source

def constant(file, name, kind=r"[\d.]+"):
    m = re.search(r"\b%s\s*=\s*(%s)F?\s*;" % (name, kind), read(JAVA / file))
    if not m:
        fail("cannot find %s in %s" % (name, file))
        return None
    return float(m.group(1))


planner, preview, repair = "track/CurvePlanner.java", "client/CurveWandPreview.java", "track/CurveRepair.java"
commands_file = "CTNCommands.java"
survey, speed = "track/SurveyMarkerBlockEntity.java", "speed/SpeedLimitEdgePoint.java"
grade = "track/GradeSpec.java"
tick_rate = constant("signal/MultiAspectSignalBlockEntity.java", "ASPECT_TICK_RATE")
joint = constant(planner, "MIN_JOINT_DOT")

# The limit on a climb is a setting, so what the READMEs say about it is checked against the setting's
# default, and a miss is reported. The rule about the height difference that was checked here before was
# found by a pattern, and once the code dropped it the pattern came back empty without a word, so the
# READMEs went on quoting a rule that no longer existed.
snap_angle = config.get("curves.snapAngle")
if snap_angle is None:
    fail("cannot find curves.snapAngle in CTNConfig")
max_grade = config.get("curves.maxGrade")
if max_grade is None:
    fail("cannot find curves.maxGrade in CTNConfig")

# (what, [expected values], English pattern, Japanese pattern); every group must match in order.
facts = [
    ("shortest span", [constant(planner, "MIN_SPAN")],
     r"less than ([\d.]+) blocks apart horizontally", r"水平距離が([\d.]+)ブロック未満"),
    ("sharpest turn", [constant(planner, "MAX_TURN_DEGREES")],
     r"turns more than (\d+) degrees", r"曲がる角度が(\d+)度を超える"),
    ("smallest radius", [constant(planner, "MIN_RADIUS")],
     r"radius is under (\d+) blocks", r"半径が(\d+)ブロック未満"),
    ("angle at a joint", [round(math.degrees(math.acos(joint))) if joint else None],
     r"by more than about (\d+) degrees where", r"角度差が約(\d+)度を超えて"),
    ("hand turning", [constant(preview, "FINE_STEP"), constant(preview, "COARSE_STEP"), constant(preview, "MAX_ADJUST")],
     r"([\d.]+) degrees per notch, (\d+) while the sprint key is held, up to ±(\d+)",
     r"1目盛り([\d.]+)度、ダッシュキーを押している間は(\d+)度で、最大±(\d+)度"),
    ("links mended per run", [constant(repair, "MAX_PER_RUN")],
     r"as two arcs, up to (\d+) per run", r"2本の円弧に直します。1回に最大(\d+)本"),
    ("links rebuilt per run", [constant(commands_file, "MAX_REBUILDS_PER_RUN")],
     r"A link that works is not touched\. Up to (\d+) per run", r"働いているリンクは変えません。1回に最大(\d+)本"),
    ("survey marker length", [constant(survey, "MIN_LENGTH"), constant(survey, "MAX_LENGTH"), constant(survey, "DEFAULT_LENGTH")],
     r"The length is (\d+) to (\d+) blocks, (\d+) by default", r"長さは(\d+)から(\d+)ブロックで、初期値は(\d+)です"),
    ("survey marker bearing", [constant(survey, "MIN_BEARING"), constant(survey, "MAX_BEARING")],
     r"The bearing is (\d+) to (\d+) degrees", r"方位は(\d+)から(\d+)度で"),
    ("speed limit range", [constant(speed, "MIN_LIMIT")],
     r"in km/h, from (\d+) up to the fastest", r"範囲は(\d+)から、Createの設定"),
    ("speed limit default", [constant(speed, "DEFAULT_LIMIT")],
     r"The limit is (\d+) by default", r"初期値は(\d+)です。向きは"),
    ("km/h in a block per second", [constant(speed, "KMH_PER_BLOCK_PER_SECOND")],
     r"one block per second counted as ([\d.]+) km/h", r"1ブロック毎秒を([\d.]+)km/hとして"),
    ("lamp refresh", [tick_rate / 20 if tick_rate else None],
     r"refresh about every ([\d.]+) seconds", r"約([\d.]+)秒ごとに更新"),
    ("grade step", [constant(grade, "STEP")],
     r"(\d+) permille per notch", r"1目盛り(\d+)‰"),
    ("steepest grade", [float(max_grade[0]) if max_grade else None],
     r"(\d+) permille by default", r"初期値は(\d+)‰"),
    ("one block in three", [round(1000 / 3)],
     r"(\d+) is one block of rise in three blocks of run", r"(\d+)は、水平3ブロックで1ブロック上がる勾配です"),
    ("angle grid window", [constant(planner, "SNAP_WINDOW") * float(snap_angle[0]) if snap_angle and constant(planner, "SNAP_WINDOW") else None],
     r"which is ([\d.]+) degrees for 15", r"15度なら([\d.]+)度です"),
    ("angle grid search", [constant(planner, "SNAP_SEARCH")],
     r"a block up to (\d+) blocks from the one you aim at", r"狙ったブロックから(\d+)ブロック以内に"),
    ("distance laid for a grade", [constant(planner, "STRAIGHT_LENGTH_TOLERANCE")],
     r"within ([\d.]+) blocks of the distance", r"指定した距離との差は([\d.]+)ブロック以内"),
]

for what, expected, en, ja in facts:
    if None in expected:
        continue  # the constant was not found, which has been reported already
    for lang, pattern in (("en", en), ("ja", ja)):
        if lang not in texts:
            continue
        found = re.findall(pattern, texts[lang])
        if not found:
            fail("%s: cannot find the %s in %s" % (READMES[lang].name, what, "the English" if lang == "en" else "the Japanese"))
            continue
        for match in found:
            values = match if isinstance(match, tuple) else (match,)
            if not all(same_number(v, e) for v, e in zip(values, expected)):
                fail("%s: the %s is %s in the code but %s here" % (READMES[lang].name, what, expected, list(values)))

if problems:
    print("The READMEs have %d problem(s):" % len(problems))
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("The READMEs agree with each other and with the source.")
