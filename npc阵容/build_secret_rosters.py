"""从原秘境任务和怪物模板导出首档四种难度的地图、对手与奖励。"""
import json
import sys
from pathlib import Path


def export(source):
    def load(name):
        return json.loads((source / (name + ".json")).read_bytes())

    def values(row):
        return [row[key] for key in sorted(row, key=int)]

    offers, teams, actors, species = map(load, ("mijing", "guaiwuteam", "guaiwu", "jingling"))
    result = {"offers": {}, "groups": {}}
    for key in ("1001", "2001", "3001", "4001"):
        offer = offers[key]
        result["offers"][key] = {name: values(value) if isinstance(value, dict) else value
                                 for name, value in offer.items()}
        for team_id in values(offer["boss"]) + values(offer["monster"]) + [offer["monster2"]]:
            if not team_id or str(team_id) in result["groups"]:
                continue
            team = teams[str(team_id)]
            members = []
            for slot, enemy in team["team"].items():
                if not enemy:
                    continue
                base = actors[str(enemy)]
                elf = species[str(base["sprite"])]
                stats = {k: v for k, v in base.items() if k not in ("type", "bigger", "level", "ptype", "skill")}
                members.append(dict(stats, enemy=enemy, position=team["formation"][slot], type1=elf["attribute1"],
                                    type2=elf["attribute2"], quality=elf["quality"], skill=values(elf["showskill"])[0]))
            result["groups"][str(team_id)] = {"actors": members, "formation": team["military"],
                                               "level": max(1, team["level"])}
    return result


if __name__ == "__main__":
    Path(sys.argv[2]).write_text(json.dumps(export(Path(sys.argv[1])), separators=(",", ":")), encoding="utf-8")
