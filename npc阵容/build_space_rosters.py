"""从原包的数值表导出究极空间实际对手、阵位及阶段奖励。"""
import argparse
import json
from pathlib import Path


def build(source):
    def read(name):
        return json.loads((source / (name + ".json")).read_bytes())

    def values(row):
        return [row[key] for key in sorted(row, key=int)]

    groups = read("guaiwuteam2")
    actors = {}
    for name in ("hangup_guaiwu", "hangup_guaiwu2", "hangup_guaiwu3"):
        actors.update(read(name))
    species = read("jingling")
    catalog = {}
    stages = {}
    milestones = {}
    for key, stage in sorted(read("space").items(), key=lambda item: int(item[0])):
        group = groups[str(stage["monster1"])]
        party = []
        for slot, enemy in group["team"].items():
            position = group["formation"][slot]
            sprite = actors[str(enemy)]["sprite"]
            row = species[str(sprite)]
            catalog[str(sprite)] = [row["stat_" + stat] for stat in (
                "hp", "physical_attack", "physical_defense", "special_attack",
                "special_defense", "speed")] + [row["attribute1"], row["attribute2"],
                row["quality"], values(row["showskill"])[0]]
            party.append([enemy, sprite, position])
        assert sorted(actor[2] for actor in party) == list(range(1, 7))
        stages[key] = [stage["nextid"], stage["monster1"], group["military"],
                       stage["attenuation"], stage["award2"], party]
        if stage.get("show"):
            milestones[key] = values(stage["show"])
    assert (len(stages), len(catalog), len(milestones)) == (4000, 341, 18)
    return {"stages": stages, "species": catalog, "milestones": milestones}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(build(args.source), separators=(",", ":")), encoding="utf-8")