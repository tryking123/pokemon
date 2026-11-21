"""提取橘子群岛地图、事件、传送池和原始敌方完整面板。"""
import json
import sys
from pathlib import Path


def export(source):
    def load(name):
        return json.loads((source / (name + ".json")).read_bytes())

    def values(row):
        return [row[k] for k in sorted(row, key=int)]

    maps, fly, teams, actors, species = map(load, ("scene", "fly", "guaiwuteam", "guaiwu", "jingling"))
    catalog = {"islands": {}, "rooms": {}, "groups": {}, "portals": {}}
    catalog["breeroom"] = {key:values(row["expend"]) for key,row in load("breeroom").items()}
    events, buffs, cards = map(load, ("event", "buff", "fanpai"))
    catalog["actions"] = {str(k):values(events[str(k)]["action1"]) for k in range(120001,120011)}
    catalog["buffs"] = {str(k):values(buffs[str(k)]["type"]) for k in range(7004,7009)}
    for island, cfg in load("tangerine").items():
        prices=cards[str(cfg["fanpai"])]
        catalog["islands"][island] = {"born": values(cfg["born"]), "cost": cfg["cos"],
                                      "level": cfg["openneed"], "clear": values(cfg["clear"]), "deck":cfg["fanpai"],
                                      "prices":[values(prices[k])[-1] for k in ("price1","price2","price3","bigprice")]}
        for room in range(cfg["born"]["1"], cfg["born"]["1"] + 5):
            scene = maps[str(room)]
            events = values(scene["eventbase"])
            fixed = values(scene.get("fixed_evenbase", {}))
            doors = values(scene["jiguan"])
            catalog["rooms"][str(room)] = {"events": events, "fixed": fixed, "count": scene["jysjnum"], "doors": doors}
            for door in doors[::3]:
                if door:
                    catalog["portals"][str(door)] = values(fly[str(door)]["position"])
            group_ids = [events[i+2] for i in range(0,len(events),3) if events[i+1] == 1]
            group_ids += [fixed[i+1] for i in range(0,len(fixed),3) if fixed[i] == 1]
            for group_id in group_ids:
                key = str(group_id)
                if key in catalog["groups"]:
                    continue
                group = teams[key]
                foes = []
                for slot, enemy in group["team"].items():
                    if not enemy:
                        continue
                    base = actors[str(enemy)]
                    elf = species[str(base["sprite"])]
                    stats = {k:v for k,v in base.items() if k not in ("type", "bigger","level","ptype","skill")}
                    foes.append(dict(stats,enemy=enemy,position=group["formation"][slot],type1=elf["attribute1"],
                                     type2=elf["attribute2"],quality=elf["quality"],skill=values(elf["showskill"])[0]))
                catalog["groups"][key] = {"actors":foes,"formation":group["military"],"level":max(1,group["level"])}
    return catalog


if __name__ == "__main__":
    Path(sys.argv[2]).write_text(json.dumps(export(Path(sys.argv[1])), separators=(",",":")),encoding="utf-8")