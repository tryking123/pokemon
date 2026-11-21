"""从底包导出的原表生成十二宫单怪池和待审核的六槽编组。"""

import argparse
import hashlib
import json
from pathlib import Path


OUTPUT = Path(__file__).with_name("zodiac-pools.json")
TABLES = ("twelve", "guaiwu", "guaiwuteam", "jingling", "twelveratio", "variable")


def build(folder):
    tables = {}
    hashes = {}
    for name in TABLES:
        raw = (folder / f"{name}.json").read_bytes()
        tables[name] = json.loads(raw)
        hashes[name] = hashlib.sha256(raw).hexdigest()
    twelve = tables["twelve"]
    assert set(twelve) == {str(i) for i in range(1, 13)}
    signs = []
    monsters = {}
    species = {}
    for palace in sorted(twelve, key=int):
        row = twelve[palace]
        assert set(row["box"]) == {str(i) for i in range(1, 31)} and row["num"] == 6
        pool = [row["box"][str(i)] for i in range(1, 31)]
        for enemy_id in pool:
            monster = tables["guaiwu"][str(enemy_id)]
            assert enemy_id not in monsters and monster["hp_max"] > 0
            monsters[enemy_id] = monster
            sid = monster["sprite"]
            template = tables["jingling"][str(sid)]
            species[sid] = {k: v for k, v in template.items() if k in
                            ("bz", "attribute1", "attribute2", "quality", "skill1", "skill2", "skill3", "showskill")}
        candidates = []
        for start in range(0, len(pool), row["num"]):
            candidates.append({str(pos): pool[start + pos - 1] for pos in range(1, 7)})
        signs.append({"palace": int(palace), "name": row["text1"], "sourceCount": row["num"],
                      "sourceGrade": row["grade"], "sourcePool": pool, "candidateSlotMaps": candidates})
    collisions = sorted(k for k in monsters if str(k) in tables["guaiwuteam"])
    assert collisions == [301001] and len(monsters) == 360
    return {
        "configVersion": "2026-11-19.zodiac.1",
        "sourceExportSha256": hashes,
        "candidateStatus": "pendingReview",
        "signs": signs,
        "sourceEnemies": monsters,
        "sourceSpeciesFields": species,
        "sourceRatioRows": tables["twelveratio"],
        "sourceRules": {k: tables["variable"][k] for k in ("2331", "2332", "2333")},
        "groupIdCollisions": collisions,
    }


def output_text(data):
    # 每个单怪、精灵和倍率行分别占一行，保留按表或宫检索的能力。
    def encode(value):
        return json.dumps(value, ensure_ascii=False)

    lines = []
    for key, value in data.items():
        if key == "signs":
            content = "[\n    " + ",\n    ".join(map(encode, value)) + "\n  ]"
        elif key in ("sourceEnemies", "sourceSpeciesFields", "sourceRatioRows"):
            entries = [encode(str(k)) + ": " + encode(value[k]) for k in sorted(value, key=int)]
            content = "{\n    " + ",\n    ".join(entries) + "\n  }"
        else:
            content = encode(value)
        lines.append("  " + encode(key) + ": " + content)
    return "{\n" + ",\n".join(lines) + "\n}\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tables", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = output_text(build(args.tables))
    if args.check:
        if OUTPUT.read_text(encoding="utf-8") != result:
            raise SystemExit("十二宫配置与原表重建结果不一致")
    else:
        OUTPUT.write_text(result, encoding="utf-8", newline="\n")
