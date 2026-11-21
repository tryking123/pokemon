"""Build or verify local NPC roster data from verified origin APK table exports."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parent
DEFAULT_OUTPUT = HERE / "local-npc-rosters.json"
ORIGIN_SHA256 = "f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45"
BASE_STATS = {
    "hp_max": "stat_hp",
    "physical_attack": "stat_physical_attack",
    "special_attack": "stat_special_attack",
    "physical_defense": "stat_physical_defense",
    "special_defense": "stat_special_defense",
    "speed": "stat_speed",
}
COMBAT_STATS = {*BASE_STATS, "hit_chance", "dodge_chance", "crit_chance", "toughness",
                "damage_bonus", "element_provide"}
DIFFICULTY = {"lower": 8000, "middle": 10000, "upper": 11500}


def integer_keys(row):
    return sorted(row, key=int)


def sequence(row):
    if isinstance(row, dict):
        return [row[key] for key in integer_keys(row)]
    return [] if row is None else [row]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Catalog:
    def __init__(self, folder):
        self.folder = folder
        self.evidence = json.loads((folder / "npc-source-evidence.json").read_text(encoding="utf-8"))
        self.tables = {}
        self.species = {}
        self.rosters = {}

    def table(self, name):
        if name not in self.tables:
            raw = (self.folder / (name + ".json")).read_bytes()
            evidence = self.evidence[name]
            if sha256(raw) != evidence["exportSha256"]:
                raise ValueError(f"Table export hash does not match {name}")
            self.tables[name] = json.loads(raw)
            if len(self.tables[name]) != evidence["rows"]:
                raise ValueError(f"Table export row count does not match {name}")
        return self.tables[name]

    def unit(self, sid, position):
        row = self.table("jingling")[str(sid)]
        skill_id = row["skill1"]["2"]
        skill = self.table("skill")[str(skill_id)]
        if sid not in self.species:
            self.species[sid] = {
                "sid": sid, "sprite": sid, "type1": row["attribute1"], "type2": row["attribute2"],
                "quality": row["quality"], "stats": {wire: row[raw] for wire, raw in BASE_STATS.items()},
                "skill": {"id": skill_id, "targetCount": skill["targetnum"], "power": skill["weili"]},
                "source": f"jingling:{sid}.skill1[2] / skill:{skill_id}",
            }
        return {"sid": sid, "position": position}

    def add(self, key, formation, units, **details):
        if str(formation) not in self.table("array"):
            raise KeyError(f"array:{formation}")
        if not units or len(units) > 6 or len({unit["position"] for unit in units}) != len(units):
            raise ValueError(f"Invalid lineup for {key}")
        if any(unit["position"] not in range(1, 7) for unit in units):
            raise ValueError(f"Unsupported position in {key}")
        row = {"id": key, "formationId": formation, "members": units, **details}
        previous = self.rosters.setdefault(key, row)
        if previous != row:
            raise ValueError(f"Conflicting NPC data for {key}")
        return key

    def group(self, group_id, enemy_tables=("guaiwu",), group_tables=("guaiwuteam", "guaiwuteam2")):
        for name in group_tables:
            row = self.table(name).get(str(group_id))
            if row and row.get("team") and row.get("formation") and row.get("military"):
                break
        else:
            raise KeyError(f"guaiwuteam:{group_id}")
        if set(row["team"]) - set(row["formation"]):
            raise ValueError(f"Group positions do not match members {group_id}")
        units = [self.original_enemy(int(row["team"][slot]), int(row["formation"][slot]), enemy_tables)
                 for slot in integer_keys(row["team"])]
        return self.add(f"{name}:{group_id}:{'+'.join(enemy_tables)}", row["military"], units,
                        source=f"{name}:{group_id}", sourceLevel=row.get("level"),
                        statsReady=all(unit.get("stats") is not None for unit in units))

    def original_enemy(self, enemy_id, pos, tables):
        for name in tables:
            enemy = self.table(name).get(str(enemy_id))
            if enemy and "sprite" in enemy:
                unit = self.unit(int(enemy["sprite"]), pos)
                unit["source"] = f"{name}:{enemy_id}"
                unit["enemyId"] = enemy_id
                stats = {k: enemy[k] for k in sorted(COMBAT_STATS) if k in enemy}
                if all(k in stats for k in BASE_STATS):
                    unit["stats"] = stats
                if "level" in enemy:
                    unit["sourceLevel"] = enemy["level"]
                return unit
        raise KeyError(f"{'/'.join(tables)}:{enemy_id}")


def mode(key, c2s, review, policy, keys, gate, minimum=1, maximum=6, **extras):
    return {
        "id": key, "c2s": c2s, "reviewStatus": review, "selection": policy,
        "playerSlots": {"min": minimum, "max": maximum},
        "pool": list(dict.fromkeys(keys)), "gate": gate, **extras,
    }


def mapped_groups(catalog, name, rows, table_order=("guaiwu",)):
    stages = {}
    unresolved = {}
    for key, group in rows:
        try:
            stages[str(key)] = catalog.group(int(group), table_order)
        except (KeyError, ValueError) as error:
            unresolved[str(key)] = str(error)
    return {"stageMap": stages, "unresolvedStageRefs": unresolved, "stageSource": name}


def training_sets(catalog, dojo):
    sets = []
    units_by_type = {k: {} for k in range(1, 19)}
    for dojo_id in range(1001, 1049):
        for group_id, _, _, formation, members in dojo["stages"][str(dojo_id)][3]:
            supported = []
            for _, position, sid, first_type, second_type, quality, skill_id in members:
                unit = catalog.unit(sid, position)
                cfg = catalog.species[sid]
                if (first_type, second_type, quality, skill_id) != (
                        cfg["type1"], cfg["type2"], cfg["quality"], cfg["skill"]["id"]):
                    raise ValueError(f"App dojo data disagrees with origin species {sid}")
                if cfg["skill"]["power"] <= 0:
                    continue
                if sid not in {item["sid"] for item in supported}:
                    supported.append(unit)
                for t in {first_type, second_type} - {0}:
                    units_by_type[t].setdefault(sid, dojo_id)
            if len(supported) == 6 and str(formation) in catalog.table("array"):
                sets.append((dojo_id, group_id, formation, sorted(supported, key=lambda u: u["position"])))
    chosen = []
    signatures = set()
    for dojo_id in range(1001, 1049, 4):
        options = [row for row in sets if dojo_id <= row[0] < dojo_id + 4]
        for index in (0, -1):
            row = options[index]
            signature = tuple(m["sid"] for m in row[3])
            if signature in signatures:
                raise ValueError("Rotation must contain 24 distinct training lineups")
            signatures.add(signature)
            key = catalog.add(f"dojo-training-{row[1]}", row[2], row[3],
                              source=f"dojo-stage:{row[0]} / group:{row[1]}", statsReady=False)
            chosen.append(key)
    type_sets = {}
    for attribute, found in units_by_type.items():
        ordered = sorted(found, key=lambda sid: (catalog.species[sid]["quality"], found[sid], sid))
        midpoint = len(ordered) // 2
        type_sets[str(attribute)] = [
            catalog.add(f"elfking-{attribute}-{tier}", 101,
                        [catalog.unit(sid, pos) for pos, sid in enumerate(sids, 1)],
                        source=f"dojo-stage species / jingling attributes:{attribute}", statsReady=False)
            for tier, sids in zip(DIFFICULTY, (ordered[:6], ordered[midpoint-3:midpoint+3], ordered[-6:]))
        ]
    return chosen, type_sets


def main_catalog(folder):
    cat = Catalog(folder)
    dojo_file = next(WORKSPACE.glob("*/src/main/resources/dojo-stage.json"))
    dojo = json.loads(dojo_file.read_bytes())
    training, king = training_sets(cat, dojo)
    solo_ids = []
    for offset, key in enumerate(training):
        unit = cat.rosters[key]["members"][offset % 6]
        solo_ids.append(cat.add(f"solo-{key}", 101, [{"sid": unit["sid"], "position": 1}],
                                source=key, statsReady=False))
    guild = mapped_groups(cat, "gonghuiboss.guaiwuteamid",
                          [(k, v["guaiwuteamid"]) for k, v in cat.table("gonghuiboss").items()])
    guild_types = {
        k: [str(v) for v in sequence(row["bossid"])]
        for k, row in cat.table("kaiqiboss").items()
    }
    world = mapped_groups(cat, "sjboss.monster",
                          [(k, v["monster"]) for k, v in cat.table("sjboss").items()])
    wild = mapped_groups(cat, "ywboss.monster",
                         [(k, v["monster"]) for k, v in cat.table("ywboss").items()])
    bandit = mapped_groups(cat, "FirstBattleData.ENCOUNTERS",
                           [(f"bandit-{i}", k) for i, k in enumerate((110007, 110008, 110009), 1)])
    display = cat.table("kfbossshow")
    boss_members = []
    for key in integer_keys(display):
        row = display[key]
        unit = cat.unit(row["sid"], row["position"])
        unit.update({name: row[name] for name in ("level", "star", "advanced")})
        boss_members.append(unit)
    any_boss = cat.add("master-worldboss-local-display", 101, boss_members,
                      source="kfbossshow:1-6", statsReady=False, sourceKind="displayOnly")
    modes = [
        mode("arena_training", [104, 105, 106, 110, 113], "approved",
             "relativePower", training, ["localCombatAdapter", "arenaDisplay"], seasonSource="businessDay"),
        mode("solo_training", [100, 101, 102, 103, 112], "approved",
             "relativePower", solo_ids, ["localCombatAdapter", "soloSlotUi"], maximum=1),
        mode("elf_king_training", [107, 108, 109, 111], "approved",
             "attributeAndRelativePower", [i for ids in king.values() for i in ids],
             ["localCombatAdapter", "elfKingTypeUi"], poolsByAttribute=king),
        mode("offline_season", [*range(234, 246), 249, 250], "approved",
             "frozenBracket", training[::2], ["localCombatAdapter", "localSeasonState", "seasonLabel"],
             seats={"player": 1, "npc": 7, "groups": 2, "slotsPerGroup": 4}),
        mode("mine_raid", [123], "approved", "frozenOccupiedSite", training[1::4],
             ["localCombatAdapter", "npcMineOccupancy", "minePositionMapping"]),
        mode("dojo_raid", [268, 269], "approved", "frozenDojoDefense", training[2::4],
             ["localCombatAdapter", "npcDojoOccupancy", "dojoIndexMapping"]),
        mode("bandit_challenge", [66, 127], "approved", "localStandIn", bandit["stageMap"].values(),
             ["localCombatAdapter", "localBanditScene"], **bandit),
        mode("bandit_boss", [68, 69, 128], "approved", "localStandIn", wild["stageMap"].values(),
             ["localCombatAdapter", "localBanditBossScene"], **wild),
        mode("guild_boss", [5070, 5071, 5072], "approved", "sourceStage", guild["stageMap"].values(),
             ["localGuild", "localCombatAdapter", "guildBossHpRule"], phasesByBossType=guild_types, **guild),
        mode("world_boss", [3651, 3652, 3653], "approved", "sourceStage", world["stageMap"].values(),
             ["localCombatAdapter", "worldBossHpRule", "partySelection"], **world),
        mode("master_world_boss", [10033, 10034, 10035, 10036], "approved", "localStandIn", [any_boss],
             ["localCombatAdapter", "verifiedOrPlannedMasterBossStats", "masterWorldBossHpRule"]),
    ]
    bounty = mapped_groups(cat, "rewardboss.guaiwuid",
                           [(k, v["guaiwuid"]) for k, v in cat.table("rewardboss").items()])
    tower = mapped_groups(cat, "tower.monsterteam",
                          [(f"{k}.{i+1}", team) for k, v in cat.table("tower").items()
                           for i, team in enumerate(sequence(v["monsterteam"])[::3])])
    daily = mapped_groups(cat, "daily.guaiwu",
                          [(k, v["guaiwu"]) for k, v in cat.table("daily").items()])
    caves = cat.table("mijing")
    cave_keys = []
    seen = set()
    for key in integer_keys(caves):
        row = caves[key]
        if row["difficulty"] not in seen:
            cave_keys.append(key)
            seen.add(row["difficulty"])
    secret = mapped_groups(cat, "mijing.boss/monster/monster2",
                           [(f"{k}.{field}.{i+1}", group) for k in cave_keys
                            for field in ("boss", "monster", "monster2")
                            for i, group in enumerate(sequence(caves[k].get(field))[:3]) if group])
    seed = cat.table("space")
    space = mapped_groups(cat, "space.monster1",
                          [(k, seed[k]["monster1"]) for k in integer_keys(seed)[::400]],
                          ("guaiwu", "hangup_guaiwu", "hangup_guaiwu2", "hangup_guaiwu3"))
    seed = cat.table("dnfpoint")
    dungeon = mapped_groups(cat, "dnfpoint.monster",
                            [(f"{k}.{i+1}", group) for k in integer_keys(seed)[::100]
                             for i, group in enumerate(sequence(seed[k]["monster"]))],
                            ("dnfguaiwu",))
    eden = mapped_groups(cat, "godanimal.monsterid", [(k, v["monsterid"])
                         for k, v in cat.table("godanimal").items()])
    nest_actors = {}
    nest_missing = {}
    for den_id, row in cat.table("den").items():
        for difficulty in range(1, 4):
            for enemy_id in sequence(row[f"monster{difficulty}"])[:2]:
                label = f"{den_id}.{difficulty}.{enemy_id}"
                try:
                    unit = cat.original_enemy(enemy_id, 2, ("denmaster",))
                    nest_actors[label] = cat.add(f"denmaster:{enemy_id}", 101, [unit], statsReady=True,
                                                source=f"denmaster:{enemy_id}", sourceKind="singleActorSample")
                except (KeyError, ValueError) as error:
                    nest_missing[label] = str(error)
    modes.extend([
        mode("bounty_boss", list(range(10012, 10021)), "conditional", "sourceStage",
             bounty["stageMap"].values(), ["activity10042", "localCombatAdapter", "bountyRules"], **bounty),
        mode("tower", [120, 121, 134], "conditional", "sourceStage", tower["stageMap"].values(),
             ["verifiedTowerStage", "localCombatAdapter", "towerBuffRules"], **tower),
        mode("daily_dungeon", [168, 169], "conditional", "sourceStage", daily["stageMap"].values(),
             ["validatedDungeonType", "localCombatAdapter", "independentDailyQuotas"], **daily),
        mode("zodiac", list(range(272, 277)), "conditional", "singleMonsterPool", [],
             ["approvedZodiacSelection", "verifiedZodiacCombatStats", "persistentCombatHpSp", "zodiacRules"],
             stageSource="twelve.box / guaiwu / jingling", candidateConfig="zodiac-pools.json",
             candidateVersion="2026-11-19.zodiac.1"),
        mode("eden", list(range(282, 287)), "conditional", "previewOnly", eden["stageMap"].values(),
             ["verifiedEdenEncounterId", "localCombatAdapter", "captureRules"], **eden),
        mode("rocket_trial", list(range(330, 337)), "conditional", "localStandIn", training[3::4],
             ["verifiedRocketMultiTeam", "localCombatAdapter", "rocketSeasonRules"]),
        mode("secret_area", [*range(5051, 5063), 5077, 5083, 5094], "conditional", "sourceStage",
             secret["stageMap"].values(), ["verifiedSecretRoomMapping", "localCombatAdapter", "secretRules"],
             stageSample="firstRoomForEachDifficulty", **secret),
        mode("nest", list(range(10038, 10043)), "conditional", "previewOnly", nest_actors.values(),
             ["verifiedNestMemberAssembly", "localCombatAdapter", "nestRules"], stageSample="twoActorsPerDenDifficulty",
             stageMap=nest_actors, unresolvedStageRefs=nest_missing, stageSource="den.monster1-3 / denmaster"),
        mode("space", list(range(5101, 5105)), "conditional", "sourceStage", space["stageMap"].values(),
             ["verifiedSpaceStage", "verifiedSpaceMonsterStatFormula", "localCombatAdapter", "spaceRules"],
             stageSample="tenSpacedCheckpoints", **space),
        mode("underground", list(range(293, 303)), "conditional", "previewOnly", dungeon["stageMap"].values(),
             ["verifiedDnfMemberAssembly", "localCombatAdapter", "undergroundRules"],
             stageSample="tenSpacedCheckpoints", **dungeon),
    ])
    blocked_guild = [(type_id, phase) for type_id, phases in guild_types.items() for phase in phases
                     if phase not in guild["stageMap"]]
    if not guild["stageMap"] or not world["stageMap"] or blocked_guild:
        raise ValueError(f"Core boss data missing {blocked_guild}")

    used_rosters = {i for item in modes for i in item["pool"]}
    final_rosters = [r for k, r in cat.rosters.items() if k in used_rosters]
    used_species = {unit["sid"] for roster in final_rosters for unit in roster["members"]}
    return {
        "schema": "pokemon-local-npc-rosters",
        "configVersion": "2026-11-19.2",
        "originApkSha256": ORIGIN_SHA256,
        "sourceTables": {k: cat.evidence[k] for k in sorted(cat.tables)},
        "appDojoCatalogSha256": sha256(dojo_file.read_bytes()),
        "activation": "requiresLocalCombatAndModeAdapter",
        "npcUidPrefix": "npc:",
        "npcLabels": {"prefix": "[NPC] ", "accountId": None, "includeInRealAccountRank": False,
                      "eligibleForRealAccountRewards": False},
        "relativeDifficulty": {
            "denominator": 10000, "multipliers": DIFFICULTY, "powerSource": "frozenLocalCombatSnapshot",
            "levelGridMin": 1, "levelGridMax": 120, "npcSlots": "truncateToPlayerCount",
            "freezeFields": ["modeId", "configVersion", "rosterId", "referencePower", "difficulty",
                             "rosterSnapshot", "playerSnapshot", "seed"],
            "fold": "absDistanceThenSeedThenRosterId",
        },
        "modes": modes,
        "formationIds": sorted({row["formationId"] for row in final_rosters}),
        "rosters": final_rosters,
        "species": {str(k): cat.species[k] for k in sorted(used_species)},
        "hiddenC2s": [*range(3026, 3029), 3032, 3034, *range(3670, 3682), 3683, 3684, 3686, 5092,
                      *range(10100, 10107), 10108, 10109, *range(20001, 20016)],
        "noNpcC2s": [10271],
    }


def verify(data):
    ids = set()
    rosters = {}
    for item in data["rosters"]:
        key = item["id"]
        assert key not in rosters, key
        rosters[key] = item
        assert item["formationId"] in data["formationIds"], key
        assert 1 <= len(item["members"]) <= 6, key
        assert len({member["position"] for member in item["members"]}) == len(item["members"]), key
        for member in item["members"]:
            assert 1 <= member["position"] <= 6, key
            actor = data["species"][str(member["sid"])]
            assert actor["sid"] == member["sid"] and int(actor["skill"]["id"]) > 0, key
            assert set(BASE_STATS) == set(actor["stats"]), key
            if item["statsReady"]:
                assert set(BASE_STATS) <= set(member.get("stats", {})), key
                assert all(member["stats"][stat] > 0 for stat in BASE_STATS), key
            ids.add(member["sid"])
    all_protocols = next(WORKSPACE.glob("*/src/main/resources/protocol/gateway.sproto.txt"))
    c2s = {int(line.split()[1]) for line in all_protocols.read_text(encoding="utf-8").splitlines()
           if line.startswith("C ")}
    hidden = set(data["hiddenC2s"]) | set(data["noNpcC2s"])
    modes = {}
    for item in data["modes"]:
        assert item["id"] not in modes and item["gate"], item["id"]
        modes[item["id"]] = item
        assert not (set(item["c2s"]) & hidden) and set(item["c2s"]) <= c2s, item["id"]
        assert set(item["pool"]) <= set(rosters), item["id"]
        assert set(item.get("stageMap", {}).values()) <= set(item["pool"]), item["id"]
        assert set(item.get("stageMap", {})).isdisjoint(item.get("unresolvedStageRefs", {}))
        assert 1 <= item["playerSlots"]["min"] <= item["playerSlots"]["max"] <= 6
        if item["reviewStatus"] == "approved":
            assert item["pool"], item["id"]
    assert 60 == len(modes["guild_boss"]["stageMap"])
    assert 3 == len(modes["world_boss"]["stageMap"])
    assert len(modes["offline_season"]["pool"]) >= modes["offline_season"]["seats"]["npc"]
    assert all(len(rosters[key]["members"]) == 1 for key in modes["solo_training"]["pool"])
    for attr, keys in modes["elf_king_training"]["poolsByAttribute"].items():
        assert all(len(rosters[key]["members"]) == 6 for key in keys)
        for key in keys:
            for unit in rosters[key]["members"]:
                props = data["species"][str(unit["sid"])]
                assert int(attr) in (props["type1"], props["type2"])
    return {"modes": len(modes), "rosters": len(rosters), "species": len(ids),
            "status": dict(Counter(item["reviewStatus"] for item in modes.values()))}


def output_text(data):
    lines = []
    for key, value in data.items():
        if key in ("rosters", "modes"):
            contents = ",\n".join("    " + json.dumps(entry, ensure_ascii=False) for entry in value)
            lines.append(f"  {json.dumps(key)}: [\n{contents}\n  ]")
        elif key == "species":
            contents = ",\n".join("    " + json.dumps(name) + ": " + json.dumps(row, ensure_ascii=False)
                                    for name, row in value.items())
            lines.append(f"  {json.dumps(key)}: {{\n{contents}\n  }}")
        else:
            lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}")
    return "{\n" + ",\n".join(lines) + "\n}\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tables", type=Path, help="Verified APK table exports and npc-source-evidence.json")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.tables:
        result = main_catalog(args.tables)
        stats = verify(result)
        args.output.write_text(output_text(result), encoding="utf-8", newline="\n")
    else:
        result = json.loads(args.output.read_text(encoding="utf-8"))
        stats = verify(result)
    print(json.dumps(stats))
