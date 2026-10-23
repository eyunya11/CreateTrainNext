#!/usr/bin/env python3
"""Splits each signal's housing.json into the models the game draws.

    python -X utf8 tools/split_signal_housing.py [model folder ...]

A signal turns in 22.5 degree steps, which a blockstate cannot express, so its housing is drawn in two
halves: the mast, which never turns, is the blockstate's model (housing_post.json and its offset
variants), and everything else, the head, is drawn by the block entity renderer (housing_head.json and
its variants). Edit housing.json in Blockbench, run this, and commit all of it. The five-aspect signal's
folder is not listed by default: its offsets were drawn by hand and split once.

For each folder given (by default the 2, 3, 4 and 6 aspect signals) it writes

    housing_head.json, housing_post.json                 the head and the mast, from housing.json
    housing_left_head.json, housing_left_post.json       the housing 6.5 pixels to the left of the mast
    housing_right_head.json, housing_right_post.json     and to the right
    housing_post_up.json, housing_left_post_up.json,     each mast again with its pole run to the top of the
    housing_right_post_up.json                           block, for when a pole or a signal stands above

The mast is every element whose footprint, with its rotation applied, lies inside the central column,
x and z both within 6.5 to 9.5: the pole. The left and right variants are made from the same housing
shifted sideways, with a second pole under it and two arms to the mast. The arms are those of the
five-aspect signal's right-hand variant (mirrored for the left), moved up or down to fit: the bottom ones
stay at the ground and the top ones go to the top of the pole. Draw housing_left.json and
housing_right.json by hand if these will not do, as was done for the five-aspect signal.

Nothing here is read by the game. It is a tool for whoever draws the models.
"""
import copy
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "src/main/resources/assets/createtrainnext/models/block"
TEMPLATE = MODELS / "multi_aspect_signal"
DEFAULT = ["multi_aspect_signal_2", "multi_aspect_signal_3", "multi_aspect_signal_4", "multi_aspect_signal_6"]

COLUMN = (6.5, 9.5)
OFFSET = 6.5
SUPPORT_TEXTURE = "createtrainnext:block/piller_support"
EPS = 1e-6


def corners(e):
    (x1, y1, z1), (x2, y2, z2) = e["from"], e["to"]
    points = [(x, y, z) for x in (x1, x2) for y in (y1, y2) for z in (z1, z2)]
    r = e.get("rotation")
    if not r or not r.get("angle"):
        return points
    a = math.radians(r["angle"])
    ox, oy, oz = r["origin"]
    out = []
    for x, y, z in points:
        x -= ox
        y -= oy
        z -= oz
        if r["axis"] == "x":
            y, z = y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)
        elif r["axis"] == "y":
            x, z = x * math.cos(a) + z * math.sin(a), -x * math.sin(a) + z * math.cos(a)
        else:
            x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        out.append((x + ox, y + oy, z + oz))
    return out


def in_column(e):
    return all(COLUMN[0] - EPS <= p[0] <= COLUMN[1] + EPS and COLUMN[0] - EPS <= p[2] <= COLUMN[1] + EPS
               for p in corners(e))


def clean(value):
    """Rounds away the noise that moving an element leaves, and the sign of a zero."""
    if isinstance(value, float):
        value = round(value, 5)
        if value == 0:
            return 0
        return int(value) if value == int(value) else value
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    return value


def shift_x(e, dx):
    e = copy.deepcopy(e)
    e["from"][0] += dx
    e["to"][0] += dx
    if e.get("rotation") and e["rotation"].get("origin"):
        e["rotation"]["origin"][0] += dx
    return clean(e)


def mirror_x(e):
    """The element reflected about the middle of the block, which an arm to the left needs from one to the right."""
    e = copy.deepcopy(e)
    e["from"][0], e["to"][0] = 16 - e["to"][0], 16 - e["from"][0]
    if e.get("rotation") and e["rotation"].get("origin"):
        e["rotation"]["origin"][0] = 16 - e["rotation"]["origin"][0]
    return clean(e)


def lift(e, top):
    """Fits a part drawn for a pole 16 pixels high to one that is `top` high.

    The arms are at the two ends of the pole and the poles run the length of it, so what is near the ground
    stays and what is near the top moves with it; an element that runs from one to the other keeps its
    bottom and moves its top."""
    e = copy.deepcopy(e)
    d = top - 16
    low, high = e["from"][1], e["to"][1]
    if high <= 2 + EPS:
        return clean(e)
    if low >= 14 - EPS:
        e["from"][1] += d
        e["to"][1] += d
        if e.get("rotation") and e["rotation"].get("origin"):
            e["rotation"]["origin"][1] += d
    else:
        e["to"][1] += d
    return clean(e)


def inline(o):
    if isinstance(o, dict):
        return "{" + ", ".join("%s: %s" % (json.dumps(k, ensure_ascii=False), inline(v)) for k, v in o.items()) + "}"
    if isinstance(o, list):
        return "[" + ", ".join(inline(v) for v in o) + "]"
    return json.dumps(o, ensure_ascii=False)


def dump(model):
    """Written the way Blockbench writes a model, so that a diff against housing.json is a diff of the elements."""
    parts = []
    for key, value in model.items():
        if key == "elements":
            rows = []
            for e in value:
                fields = []
                for k, v in e.items():
                    if k == "faces":
                        faces = ",\n".join("\t\t\t\t%s: %s" % (json.dumps(fk), inline(fv)) for fk, fv in v.items())
                        fields.append('\t\t\t"faces": {\n%s\n\t\t\t}' % faces)
                    else:
                        fields.append("\t\t\t%s: %s" % (json.dumps(k, ensure_ascii=False), inline(v)))
                rows.append("\t\t{\n" + ",\n".join(fields) + "\n\t\t}")
            parts.append('\t"elements": [\n' + ",\n".join(rows) + "\n\t]")
        elif key == "textures":
            parts.append('\t"textures": {\n' + ",\n".join(
                "\t\t%s: %s" % (json.dumps(k), json.dumps(v)) for k, v in value.items()) + "\n\t}")
        else:
            parts.append("\t%s: %s" % (json.dumps(key), json.dumps(value, ensure_ascii=False)))
    return "{\n" + ",\n".join(parts) + "\n}\n"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(folder, name, model):
    (folder / name).write_text(dump(model), encoding="utf-8", newline="\n")
    print("wrote %s/%s (%d elements)" % (folder.name, name, len(model["elements"])))


def reach(elements):
    """The mast with its pole running exactly to the top of the block.

    A signal whose pole stops short of the block above it, or runs on into it, is joined to what stands
    there by this one. The five-aspect's pole already ends at 16, so it has none."""
    out = copy.deepcopy(elements)
    for e in out:
        if e.get("name") in ("pole", "center_pole"):
            e["to"][1] = 16
    return out


def credit(what, source="housing.json"):
    return ("Generated by tools/split_signal_housing.py from %s. The block turns in 22.5 degree steps, which a "
            "blockstate cannot express, so the head is drawn by the block entity renderer and only the mast, "
            "the pole in the central column (x and z within 6.5 to 9.5), is a blockstate model. Edit %s and run "
            "the script again, never this file. This is %s." % (source, source, what))


def templates():
    """The five-aspect signal's right-hand arms and mast, which the other signals borrow."""
    head = read(TEMPLATE / "housing_right_head.json")["elements"]
    post = read(TEMPLATE / "housing_right_post.json")["elements"]
    pole, arms = head[0], head[162:]
    if pole.get("name") != "pole" or len(arms) != 10 or len(post) != 5 or arms[0]["from"] != [13.4, 14, 6.9]:
        sys.exit("housing_right_head.json is not laid out the way this script expects; look at templates()")
    return pole, arms, post


def split(folder):
    housing = read(folder / "housing.json")
    elements = housing["elements"]
    mast = [e for e in elements if in_column(e)]
    head = [e for e in elements if not in_column(e)]
    if len(mast) != 1 or mast[0].get("name") != "pole":
        sys.exit("%s: expected the pole alone in the central column, found %d elements" % (folder.name, len(mast)))
    top = mast[0]["to"][1]
    textures = dict(housing["textures"])

    write(folder, "housing_post.json", {"credit": credit("the mast"), "textures": textures, "elements": mast})
    write(folder, "housing_post_up.json", {"credit": credit("the mast with its pole run to the top of the block"),
                                           "textures": textures, "elements": reach(mast)})
    write(folder, "housing_head.json", {"format_version": housing.get("format_version", "1.21.11"),
                                        "credit": credit("the turning head"), "textures": textures,
                                        "elements": head})

    pole, arms, post = templates()
    side_textures = dict(textures)
    side_textures["2"] = SUPPORT_TEXTURE
    for side, dx in (("left", -OFFSET), ("right", OFFSET)):
        fit = (lambda e: mirror_x(e)) if side == "left" else (lambda e: e)
        support = [lift(fit(e), top) for e in [pole] + arms]
        body = [shift_x(e, dx) for e in head]
        write(folder, "housing_%s_head.json" % side,
              {"format_version": housing.get("format_version", "1.21.11"),
               "credit": credit("the turning head, with the housing %s of the mast" % side),
               "textures": side_textures, "elements": support[:1] + body + support[1:]})
        mast_here = [lift(e, top) for e in post]
        write(folder, "housing_%s_post.json" % side,
              {"credit": credit("the mast, with collars where the arms meet it"), "textures": side_textures,
               "elements": mast_here})
        write(folder, "housing_%s_post_up.json" % side,
              {"credit": credit("the mast, with collars where the arms meet it, and its pole run to the top of "
                                "the block"), "textures": side_textures, "elements": reach(mast_here)})


def main():
    folders = sys.argv[1:] or DEFAULT
    for name in folders:
        folder = MODELS / name
        if not (folder / "housing.json").exists():
            sys.exit("%s has no housing.json" % folder)
        split(folder)


if __name__ == "__main__":
    main()
