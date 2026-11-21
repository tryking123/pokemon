"""从固定底包的旅行战斗事件生成 FixedStageCombatService 对手配置。"""
import json
import sys
from pathlib import Path


def export(events, tables):
    def read(name):
        return json.loads((tables / (name + ".json")).read_bytes())

    teams, monsters, species = map(read, ("guaiwuteam", "guaiwu", "jingling"))
    mapping = {}
    groups = {}
    for event_id, row in events.items():
        if row.get("battle") != 1:
            continue
        team_id = str(row["monster"])
        mapping[str(event_id)] = int(team_id)
        if team_id in groups:
            continue
        team = teams[team_id]
        actors = []
        for slot, enemy in team["team"].items():
            if not enemy:
                continue
            monster = monsters[str(enemy)]
            elf = species[str(monster["sprite"])]
            actor = {k: v for k, v in monster.items() if k not in ("type", "bigger", "level", "ptype", "skill")}
            actor.update(enemy=enemy, position=team["formation"][slot], type1=elf["attribute1"],
                         type2=elf["attribute2"], quality=elf["quality"], skill=elf["showskill"]["1"])
            assert actor["hp_max"] > 0 and 1 <= actor["position"] <= 6 and actor["skill"] > 0
            actors.append(actor)
        assert len(actors) == len({actor["position"] for actor in actors}) and actors
        groups[team_id] = dict(formation=team["military"], level=max(1, team["level"]), actors=actors)
    assert len(mapping) == 26
    return {"events": mapping, "groups": groups}


if __name__ == "__main__":
    events_file, tables, output = map(Path, sys.argv[1:])
    output.write_text(json.dumps(export(json.loads(events_file.read_bytes()), tables), separators=(",", ":")),
                      encoding="utf-8")
