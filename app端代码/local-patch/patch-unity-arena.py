"""从已校验底包生成本地竞技界面的双架构资源补丁。

需要 Python 3.11 或以上、pycryptodome、UnityPy。环境自带依赖时可直接运行，
也可以用 --python-libs 指向已有依赖目录。脚本不联网，不修改底包或解包目录。
"""

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import zipfile
from patch_guild_construction import patch_guild_build
from patch_tablet_supply import patch_tablet_lua, append_tablet_protocol
from patch_unlimited_adventure import patch_unlimited
from patch_activity_removal import patch_activity_removal, patch_activity_shortcuts

ORIGIN_SHA256 = "f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45"
ASSETS = "assets/assets/"
METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
# 已校验底包 global-metadata 的 Unity 资源密钥字符串字节位置。
METADATA_LITERAL_OFFSET = 86192
APP_RESOURCES = Path(__file__).resolve().parent / "offline/resources"
RATING_RULES = APP_RESOURCES / "catalog/equipment-rating.json"
OP = {"ISEQV": 4, "LEN": 21, "ADDVN": 22, "FORI": 77, "FORL": 79, "ISLE": 2, "KNUM": 42, "MOV": 18, "KSHORT": 41, "KPRI": 43, "UGET": 45, "TGETS": 57,
      "ADDVV": 32, "SUBVV": 33, "DIVVV": 35, "CAT": 38, "KSTR": 39, "TSETS": 61,
      "TGETB": 58, "CALL": 66, "RET0": 75, "RET1": 76,
      "ISNEV": 5, "GGET": 54, "IST": 14, "JMP": 88, "TNEW": 52, "TDUP": 53, "USETV": 46,
      "MULVV": 34, "MODVV": 36, "TGETV": 56, "TSETV": 60, "ISF": 15, "UCLO": 50, "FNEW": 51}

class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def raw(self, length):
        end = self.pos + length
        assert end <= len(self.data)
        item = self.data[self.pos:end]
        self.pos = end
        return item

    def num(self):
        value, shift = 0, 0
        while True:
            byte = self.raw(1)[0]
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7

def encode(value):
    data = bytearray()
    while value >= 128:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)

def lv(reader):
    kind = reader.num()
    if kind >= 5:
        return reader.raw(kind - 5)
    if kind == 3:
        return int(reader.num())
    if kind == 4:
        return (reader.num(), reader.num())
    return {0:None, 1:False, 2:True}[kind]

def pack(value):
    if value is None:
        return b"\0"
    if type(value) is bool:
        return bytes([2 if value else 1])
    if type(value) is int:
        return b"\3" + encode(value)
    if type(value) is bytes:
        return encode(5 + len(value)) + value
    return b"\4" + b"".join(map(encode, value))

@dataclass
class Template:
    array: list
    fields: dict

def table(reader):
    length, count = reader.num(), reader.num()
    return Template([lv(reader) for _ in range(length)], {lv(reader):lv(reader) for _ in range(count)})

def constants(reader, count):
    result = []
    for _ in range(count):
        start = reader.pos
        kind = reader.num()
        if kind == 1:
            result.append(table(reader))
            continue
        if kind >= 5:
            reader.raw(kind - 5)
        elif kind != 0:
            for ignored in range(4 if kind == 4 else 2):
                reader.num()
        result.append(reader.data[start:reader.pos])
    return result

def serial_constant(constant):
    if type(constant) is bytes:
        return constant
    return (b"\x01" + encode(len(constant.array)) + encode(len(constant.fields))
            + b"".join(map(pack, constant.array))
            + b"".join(pack(k) + pack(v) for k,v in constant.fields.items()))

def adjust_variables(pool, counts):
    ordered = list(reversed(pool))
    for obj in ordered:
        if isinstance(obj, Template) and obj.fields.get(b"name", b"").startswith("超速闯关每次最大闯关数量".encode()):
            assert obj.fields[b"data1"] == 10
            obj.fields[b"data1"] = 100
            counts["百关钻石闯关"] += 1
    for i, obj in enumerate(ordered):
        if isinstance(obj, Template) and obj.fields.get(b"name", b"").startswith("悬赏BOSS免费刷新时间间隔".encode()):
            assert obj.fields[b"data3"] == 999 and ordered[i+1].array[1:5] == [500,500,1000,1000]
            obj.fields[b"data3"] = 2
            ordered[i+1].array = [None,300,300]
            counts["悬赏首领次数和购买价格"] += 1
    for i, obj in enumerate(ordered):
        if isinstance(obj, Template) and obj.fields.get(b"name", b"").startswith(
                ("家园数字华容道每日免费次数".encode(), "家园连连看每日免费次数".encode())):
            assert obj.fields[b"data1"] == 2 and obj.fields[b"data2"] == 0
            assert ordered[i+1].array == [None, 0]
            obj.fields[b"data2"] = 3
            ordered[i+1].array = [None, 50, 100, 150]
            counts["家园遗迹购次"] += 1
    selected = {
        "天梯竞赛基础挑战次数": {b"data1": (5, 5), b"data2": (30, 150)},
        "单挑之王基础挑战次数": {b"data1": (5, 3), b"data2": (30, 150)},
        "精灵大师基础挑战次数": {b"data1": (5, 3), b"data2": (30, 200)},
        "公会创建需要花费的钻石数量": {b"data1": (5000, 100000)},
        "公会捐献每天总计捐献次数": {b"data1": (5, 3)},
        "每次金币捐献消耗的货币数量": {b"data1": (100000, 10000), b"data4": (100, 20)},
    }
    for obj in pool:
        if not isinstance(obj, Template):
            continue
        name = obj.fields.get(b"name", b"")
        if not isinstance(name, bytes):
            continue
        text = name.decode("utf-8", errors="replace")
        for prefix, edits in selected.items():
            if text.startswith(prefix):
                for field, (previous, replacement) in edits.items():
                    assert obj.fields[field] == previous, (prefix, field)
                    obj.fields[field] = replacement
                counts[prefix] += 1

def adjust_limits(pool, counts):
    for obj in pool:
        if not isinstance(obj, Template):
            continue
        for name, value in [(b"pktime",2),(b"dtpktime",1),(b"jlpktime",1),(b"stone",8),(b"buyap",3)]:
            if name in obj.fields:
                obj.fields[name] = value
                counts[name] += 1

def adjust_prizes(pool, instructions, counts):
    pool = list(reversed(pool))
    assert len(instructions) == 18 * 4
    assert len(pool) == 9
    tiers = [(40,21063,1),(100,20027,2),(180,21058,1)]
    for i, (threshold,item,quantity) in zip((0,3,5),tiers):
        assert b"number" in pool[i].fields
        assert pool[i+1].array[0] is None
        pool[i].fields[b"number"] = threshold
        pool[i+1].array = [None,1,item,quantity]
    counts["weekly"] += 1
    return list(reversed(pool[:-2])), instructions[:13*4] + instructions[-4:]

def update_lua(source, mode):
    if mode.startswith('unlimited_'):
        return patch_unlimited(source, mode, Reader, encode, constants, serial_constant, pack, ins)
    if mode in ("tablet_npc", "tablet_helper"):
        return patch_tablet_lua(source, mode, Reader, encode, constants, serial_constant, pack, ins)
    if mode == "guild_unlimited_build":
        return patch_guild_build(source, Reader, encode, constants, serial_constant, pack, ins)
    if mode == "daily_voucher_labels":
        original = b"\x09166%\x09200%\x09160%\x09200%"
        assert source.count(original) == 1
        # 原两档各按支付面额显示券值比例，第二档四舍五入为整数。
        return source.replace(original, b"\x09500%\x09161%\x09160%\x09200%"), Counter(daily_voucher_labels=1)
    if mode == "hunt_time":
        original = "狩猎场每天凌晨0点刷新".encode()
        assert source.count(original) == 1
        return source.replace(original, "狩猎场每天凌晨4点刷新".encode()), Counter(hunt_time=1)
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and flags & 1 == 0
    first_argument = 2 if flags & 8 else 1
    patched = bytearray(source[:cur.pos])
    counts = Counter()
    index = 0
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nconstants, nnumbers, nop = [proto.num() for _ in range(3)]
        instructions = proto.raw(nop * 4)
        variables = proto.raw(head[3] * 2)
        pool = constants(proto, nconstants)
        numbers = proto.data[proto.pos:]
        if mode == "map_unlock":
            # 地图页按已通关关号判断。已达到本图通关奖励门槛时也应能进入。
            for row in pool:
                if isinstance(row, Template) and b"rewardlevel" in row.fields:
                    fields = row.fields
                    if fields[b"needlevel"] > fields[b"rewardlevel"]:
                        fields[b"needlevel"] = fields[b"rewardlevel"]
                        counts[fields[b"door"]] += 1
        if mode == "pcl_unlock":
            # 原 PCL 入口仍标记未开放；只打开 1195 对应行，保留训练家 80 级门槛。
            for row in pool:
                if isinstance(row, Template) and row.fields.get(b"name") == 2182:
                    assert row.fields == {b"study": 0, b"name": 2182, b"gatelevel": 80, b"door": 0}
                    row.fields[b"door"] = 1
                    counts[mode] += 1
        if mode == "speed_state" and index == 2:
            assert nop == 85
            slot = head[2]
            arg = slot + first_argument
            key = len(pool)
            chunk = b'local x=...; local d={}; for k,v in pairs(gnavi_model.get_hangup_data()) do d[k]=v end; d.mission=x.data.final; gnavi_model.copy_hangup(d)'
            pool = [pack(chunk),pack(b"loadstring")] + pool
            instructions = (instructions[:75*4] + ins("GGET",slot,key) + ins("KSTR",arg,key+1)
                + ins("CALL",slot,514) + ins("UGET",arg,0) + ins("CALL",slot,258) + ins("RET0",0,1))
            head = head[:2]+bytes([arg+1])+head[3:]
            counts["speed_state"] += 1
        if mode == "skill_upgrade_panel" and index == 10:
            # 可升级技能打开原升级弹窗，其余技能保留原详情。
            assert instructions[11*4:13*4] == ins("GGET",3,4) + ins("ISF",0,3)
            instructions = instructions[:11*4] + ins("TGETS",3,2*256+16) + ins("IST",0,3) + instructions[13*4:]
            counts[mode] += 1
        if mode == "skill_upgrade_rows":
            # 此模块的五个 BT 开关控制等级、按钮、文字及高度。
            for offset in range(0,len(instructions),4):
                op, reg, key = struct.unpack("<BBH", instructions[offset:offset+4])
                if op == OP["GGET"] and pool[-1-key] == pack(b"IS_BT"):
                    instructions = instructions[:offset] + ins("KPRI",reg,1) + instructions[offset+4:]
                    counts[mode] += 1
            if index == 9:
                # 以基础技能为稳定索引，进化或换装后仍对应同一技能。
                assert instructions[26*4:27*4] == ins("TGETS",3,10)
                instructions = instructions[:26*4] + ins("TGETS",3,6) + instructions[27*4:]
        if mode == "skill_upgrade_submit" and index == 9:
            assert nop == 50
            chunk = ("local self = ...; if self.anim4.gameObject.activeSelf then return true end; "
                     "label_popup.show('请放入一只同属性或两只异属性材料精灵，并备齐所示道具。'); return false").encode()
            key = len(pool)
            pool = [pack(chunk),pack(b"loadstring")] + pool
            arg = 2 + first_argument
            prefix = (ins("GGET",2,key) + ins("KSTR",arg,key+1) + ins("CALL",2,514)
                      + ins("MOV",arg,1) + ins("CALL",2,514) + ins("IST",0,2)
                      + ins("JMP",3,0x8001) + ins("RET0",0,1))
            instructions = prefix + instructions
            counts[mode] += 1
        if mode == "skill_upgrade_sync" and index == 82:
            # S1001 先给出实际等级，动画结束只广播刷新。
            assert nop == 41 and ins("TSETS",2,256+5) == instructions[25*4:26*4]
            instructions = instructions[:13*4] + instructions[33*4:]
            counts[mode] += 1
        if mode == "relic_counts" and index in (6,15):
            assert nop == 2 and instructions == ins("USETV",0,0) + ins("RET0",0,1)
            pool = [pack(b"SMALLGAME_NUM"),pack(b"Brocast"),pack(b"Event")]
            arg = 1 + first_argument
            instructions = (instructions[:4] + ins("GGET",1,0) + ins("TGETS",1,257)
                            + ins("KSTR",arg,2) + ins("CALL",1,258) + instructions[-4:])
            head = head[:2] + bytes([max(head[2], arg+1)]) + head[3:]
            counts[mode] += 1
        if mode == "vip_sign" and index == 0:
            assert instructions[-4:] == ins("RET1",0,2)
            key = len(pool)
            chunk = b"local rows=...; for i=1,30 do local r=assert(rows[i]); r.viplevel=1; r.multiple=2 end"
            pool = [pack(chunk),pack(b"loadstring")] + pool
            slot = head[2]
            arg = slot + first_argument
            instructions = (instructions[:-4]+ins("GGET",slot,key)+ins("KSTR",arg,key+1)+ins("CALL",slot,514)
                            +ins("MOV",arg,0)+ins("CALL",slot,258)+instructions[-4:])
            head = head[:2]+bytes([arg+1])+head[3:]
            counts[mode] += 1
        if mode == "stamina_popup" and index == 8:
            assert nop == 50 and instructions[-4:] == ins("RET0",0,1)
            instructions = instructions[:-4] + ins("UGET",0,1) + ins("CALL",0,257) + instructions[-4:]
            counts[mode] += 1
        if mode == "stamina_popup" and index in (2, 10):
            slot = head[2]
            arg = slot + first_argument
            key = len(pool)
            if index == 10:
                assert nop == 77 and pool[-4] == pack(b"BuyThiredPopup")
                chunk = Path(__file__).with_name("stamina-exchange.lua").read_bytes()
                body = instructions[:-4]
                args = ins("UGET",arg,0) + ins("UGET",arg+1,1)
            else:
                assert nop == 53 and pool[-6] == pack(b"tiredpower")
                chunk = """local ui=...; local role=chr_model.get_chr(); local n=role.thired_time or 0
ui.timestr.text = "当前体力：" .. tostring(role.tiredpower or 100)
ui.dianum.text = n >= 3 and "今日已购满" or tostring(50*(1+n))
ui.buynum.text = tostring(math.max(0,3-n))""".encode()
                body = instructions[:8]
                args = ins("UGET",arg,3)
            pool = [pack(chunk),pack(b"loadstring")] + pool
            instructions = (body + ins("GGET",slot,key)+ins("KSTR",arg,key+1)+ins("CALL",slot,514)+args+
                            ins("CALL",slot,259 if index==10 else 258)+ins("RET0",0,1))
            head = head[:2]+bytes([arg+2])+head[3:]
            counts[mode] += 1
        if mode == "dungeon_hook_row" and index == 10:
            assert nop == 81 and instructions[-8:-4] == ins("TSETS",3,2*256+24)
            key = len(pool)
            chunk = Path(__file__).with_name("dungeon-hook-row.lua").read_bytes()
            pool = [pack(chunk),pack(b"loadstring")] + pool
            arg = 4 + first_argument
            instructions = (instructions[:-4] + ins("GGET",4,key) + ins("KSTR",arg,key+1)
                            + ins("CALL",4,514) + ins("MOV",arg,2) + ins("UGET",arg+1,1)
                            + ins("CALL",4,259) + instructions[-4:])
            counts[mode] += 1
        if mode == "dungeon_hook_model" and index == 2:
            # 此应答也服务于只读刷新，具体状态以原 gdun_model 更新后的 S372 为准。
            assert nop == 18 and pool[-6] == pack(b"HOOK_NAME")
            instructions = instructions[:4*4] + instructions[-4:]
            counts[mode] += 1
        if mode == "dungeon_hook_panel" and index in (2,3,6):
            key = len(pool)
            if index == 2:
                assert nop == 186 and pool[-34] == pack(b"autoreward")
                chunk = b'local v=...; v.localHookRedraw(); Network.Request("ask_onhook",{sid=-1})'
                body = b""
            elif index == 3:
                assert nop == 96 and pool[-24] == pack(b"Enter")
                chunk = Path(__file__).with_name("dungeon-hook-panel.lua").read_bytes()
                body = instructions[:89*4]
            else:
                assert nop == 2 and variables == struct.pack("<H",0x8002)
                variables += struct.pack("<H",0xc001)
                head = head[:3] + bytes([2])
                chunk = b'local v=...; if v.localHookRedraw then Event.RemoveListener("UPDATE_HOOK",v.localHookRedraw); v.localHookRedraw=nil end'
                body = instructions[:4]
            pool = [pack(chunk),pack(b"loadstring")] + pool
            arg = first_argument
            body += ins("GGET",0,key) + ins("KSTR",arg,key+1) + ins("CALL",0,514)
            body += ins("UGET",arg,0 if index == 2 else 1)
            if index == 3:
                body += ins("UGET",arg+1,2)
            instructions = body + ins("CALL",0,259 if index == 3 else 258) + ins("RET0",0,1)
            head = head[:2] + bytes([max(head[2],arg+2)]) + head[3:]
            counts[mode] += 1
        if mode == "race_grid" and index == 2:
            # 原 energy 处理器的负数来自冲刺或护盾；物件能量收益由原碰撞三元组推导。
            assert nop == 27 and head[3] == 3 and pool[-2] == pack(b"energy")
            slot = head[2]
            arg = slot + first_argument
            base = len(pool)
            hook = (ins("KSHORT",slot,0) + ins("ISLE",slot,0) + ins("JMP",slot,0x8005)
                    + ins("GGET",slot,base) + ins("TGETS",slot,(slot<<8)|(base+1))
                    + ins("UGET",arg,head[3]) + ins("MOV",arg+1,0)
                    + ins("CALL",slot,(1<<8)|3))
            instructions = instructions[:-4] + hook + instructions[-4:]
            pool = [pack(b"insert"),pack(b"table")] + pool
            variables += bytes.fromhex("0880")
            head = head[:2] + bytes((arg+2,head[3]+1))
            counts[mode] += 1
        if mode == "race_grid" and index == 12:
            # set_oper 捕获当前段的碰撞记录；add_score 共用这一份记录。
            assert variables == bytes.fromhex("0880") and nop == 2
            race_operations = variables
        if mode == "race_grid" and index == 14:
            assert head[3] == 1 and nop == 6 and pool[-1] == pack(b"score")
            # 经过原格线时追加单个 -1，原物件碰撞仍用三个非负整数。
            base = len(pool)
            arg = 3 + first_argument
            hook = (ins("GGET", 3, base) + ins("TGETS", 3, (3<<8)|(base+1))
                    + ins("UGET", arg, 1) + ins("KSHORT", arg+1, 65535)
                    + ins("CALL", 3, (1<<8)|3))
            instructions = instructions[:-4] + hook + instructions[-4:]
            pool = [pack(b"insert"), pack(b"table")] + pool
            variables += race_operations
            head = head[:2] + bytes((max(head[2],arg+2),2))
            counts[mode] += 1
        if mode == "world_regular" and index in (11,15,18) or mode == "world_master" and index in (4,12):
            # 原世界首领详情的寻路与编队保留，本地领奖、明示报价和实有次数走该窗口的原控件。
            if mode == "world_regular":
                assert nop == {11:334,15:183,18:57}[index]
                args=(0,1) if index==11 else (0,2)
                chunk={
                    11: """
local popup,view=...
local row=popup.data.data[1]
utils.remove_click_event(view.reward_btn)
utils.add_click_event(view.reward_btn,function()
    if (row.reward_index or 0)<1 then view.reward:SetActive(true) return end
    msg_popup.show("领取一场已达标的世界首领伤害奖励？取消可查看周榜奖励。",nil,2,function(ok)
        if ok then
            Network.Request("get_worldboss_reward",{uid=row.uid})
            popup.close()
            gpandect_model.open_sjboss()
        else view.reward:SetActive(true) end
    end)
end)
if popup.fuc then
    utils.remove_click_event(view.battle)
    utils.add_click_event(view.battle,function()
        if row.cur_hp<=0 then label_popup.show("本场首领已被击败") return end
        if (popup.data.challenge_num or 0)>0 then popup.close() Network.Request("challenge_worldboss",{uid=row.uid}) return end
        if row.local_bought then label_popup.show("今日次数已用完") return end
        msg_popup.show("花费200钻石挑战世界首领？每日可额外购买一次。",nil,2,function(ok)
            if ok then popup.close() Network.Request("challenge_worldboss",{uid="buy:"..row.uid}) end
        end)
    end)
end
""",
                    15: """
local popup,view=...
local row=popup.data.data[1]
view.count.text="剩余挑战次数："..tostring(popup.data.challenge_num or 0).."　待领奖："..tostring(row.reward_index or 0)
""",
                    18: """
local popup,view=...
view.reward_txt.text="有效伤害达2% / 10% / 30%可领取当场最高档，周榜奖励通过邮件发放。"
view.layout.DataCount=#(popup.data.data[1].reward_tab or {})
view.layout:InvalidateData()
"""}[index]
            else:
                assert nop == {4:56,12:69}[index]
                args=(0,) if index==4 else (0,1)
                chunk={
                    4:"""
local view=...
if (view.data.hp or 0)<=0 then label_popup.show("本场首领已被击败") return end
if (view.data.challenge_num or 0)>0 then view:brocast("click_battle") return end
if (view.data.buy_num or 0)>0 then label_popup.show("今日次数已用完") return end
msg_popup.show("花费200钻石购买一次大师世界首领挑战？",nil,2,function(ok) if ok then view:brocast("buy_count") end end)
""",
                    12:"""
local state,view=...
local left=state.data.challenge_num or 0
view.count.gameObject:SetActive(true)
view.count.text="剩余挑战次数："..tostring(left)
view.cost:SetActive(left==0 and (state.data.buy_num or 0)==0)
view.cost_txt.text="200"
"""}[index]
            key=len(pool)
            pool=[pack(chunk.encode()),pack(b"loadstring")]+pool
            call=ins("GGET",0,key)+ins("KSTR",first_argument,key+1)+ins("CALL",0,514)
            for i,uv in enumerate(args):
                call+=ins("UGET",first_argument+i,uv)
            call+=ins("CALL",0,256+1+len(args))+ins("RET0",0,1)
            assert instructions[-4:]==ins("RET0",0,1)
            append=(mode=="world_regular" and index in (11,15))
            instructions=(instructions[:-4] if append else b"")+call
            head=head[:2]+bytes([max(head[2],first_argument+len(args)+1)])+head[3:]
            counts[mode]+=1
        if mode == "blind_sales" and index == 0:
            swaps={406:402,407:407,408:403}
            for row in pool:
                if isinstance(row,Template) and row.fields.get(b"recharge") in swaps:
                    row.fields[b"recharge"]=swaps[row.fields[b"recharge"]]
                    counts[mode]+=1
        if mode == "blind_payment" and index == 0:
            # 50份精灵档在原付款表缺行，按原单位价格及原同类支付字段生成。
            row=next(x for x in pool if isinstance(x,Template) and x.fields.get(b"name")=="大量精灵盲盒".encode())
            fields=dict(row.fields)
            fields.update({b"name":"精灵盲盒×50".encode(), b"chargeValue":1000, b"chargeValue2":1000,
                           b"Recharge":1000000, b"ifvoucher":1000, b"produceId":9407, b"produceIdios":b"9407"})
            key=len(pool)
            pool=[Template([],fields)]+pool
            assert instructions[-4:]==ins("RET1",0,2)
            instructions=instructions[:-4]+ins("TDUP",1,key)+ins("KSHORT",2,407)+ins("TSETV",1,2)+instructions[-4:]
            counts[mode]+=1
        if mode == "dnf_clock" and index == 10:
            # 原面板查到赛季行后，按总览 S1101 的本周起止刷新展示日期。
            assert nop == 212 and instructions[103*4:104*4] == ins("CALL", 6, 515)
            chunk = b"""
local config = ...
local window = gpandect_model.get_data(28)
if window then
    config.opentime = os.date("%Y/%m/%d/%H/%M/%S", tonumber(window.openTime))
    config.endtime = os.date("%Y/%m/%d/%H/%M/%S", tonumber(window.endTime) - 1)
end
"""
            key = len(pool)
            pool = [pack(chunk), pack(b"loadstring")] + pool
            arg = 7 + first_argument
            dates = (ins("GGET", 7, key) + ins("KSTR", arg, key + 1) + ins("CALL", 7, 514)
                     + ins("MOV", arg, 6) + ins("CALL", 7, 258))
            instructions = instructions[:104*4] + dates + instructions[104*4:]
            counts[mode] += 1
        if mode == "free_sign_prompt" and index == 0:
            labels = pool[0].array
            assert labels[10] == "是否花费%d钻石进行一次补签?".encode()
            labels[10] = "免费补签一次，领取本轮最早漏签日的奖励？".encode()
            counts[mode] += 1
        if mode == "blind_help" and index == 0:
            row=pool[0]
            assert isinstance(row,Template) and "盲盒".encode() in row.array[15]
            row.array[15]=("精灵盲盒每份随机得1只，存入精灵银行（上限5000只）。"
                           "勾选后仅自动分解红色精灵。粉色0.0875%，金色41.3125%，红色58.6%。"
                           "时装盲盒每份得同款紫色时装碎片3片，紫色100%。"
                           "精灵1/10/50/100份和时装1/10份按每份20元模拟支付，购买本身不赠代金券。").encode()
            counts[mode]+=1
        if mode == "pet_customize" and index == 13:
            assert nop == 322 and pack(b"BG/Aptitude") not in pool and pack(b"Aptitude") in pool
            assert instructions[-4:] == ins("RET0", 0, 1)
            key = len(pool)
            chunk = Path(__file__).with_name("pet-customize-button.lua").read_bytes()
            pool = [pack(chunk), pack(b"loadstring")] + pool
            slot = head[2]
            arg = slot + first_argument
            instructions = (instructions[:-4] + ins("GGET", slot, key) + ins("KSTR", arg, key + 1)
                            + ins("CALL", slot, 514) + ins("UGET", arg, 1)
                            + ins("CALL", slot, 258) + instructions[-4:])
            head = head[:2] + bytes([arg + 1]) + head[3:]
            counts[mode] += 1
        if mode == "blind_bank_capacity" and index == 4:
            assert nop == 67 and head[1] == 2 and head[3] == 0
            assert pack(b"bagmax") in pool and pack(b"not_battle") in pool
            key = len(pool)
            chunk = Path(__file__).with_name("blind-bank-capacity.lua").read_bytes()
            pool = [pack(chunk), pack(b"loadstring")] + pool
            arg = 2 + first_argument
            instructions = (ins("GGET", 2, key) + ins("KSTR", arg, key + 1)
                            + ins("CALL", 2, 514) + ins("MOV", arg, 1)
                            + ins("CALL", 2, 514) + ins("RET1", 2, 2))
            head = head[:2] + bytes([max(head[2], arg + 1)]) + head[3:]
            counts[mode] += 1
        if mode == "blind_global" and index == 0:
            # 原红点更新后通知商城刷新权威完整余额。
            key=len(pool); pool=[pack(b"LOCAL_BLIND_BOX")]+pool
            assert nop==7 and instructions[-4:]==ins("RET0",0,1)
            instructions=instructions[:-4]+instructions[4:12]+ins("KSTR",1+first_argument,key)+ins("CALL",1,258)+instructions[-4:]
            counts[mode]+=1
        if mode == "blind_screen" and index == 5:
            assert pool[-10]==pack(b"GET_REWARD")
            pool[-10]=pack(b"LOCAL_BLIND_BOX")
            counts[mode]+=1
        if mode == "blind_screen" and index == 4:
            # 面板仍打开时覆写银行子页的余额，并保留原主面板刷新。
            key=len(pool)
            pool=list(reversed([pack(x) for x in (b"loadview",b"oneshop_bag",b"goneshop_model",b"get_data",b"setdata",b"bank")]))+pool
            a=first_argument
            extra=(ins("UGET",1,0)+ins("TGETS",1,256)+ins("MOV",1+a,1)+ins("TGETS",1,256+key)
                   +ins("KSTR",2+a,key+1)+ins("CALL",1,515)+ins("TGETS",2,257)+ins("IST",0,2)
                   +ins("JMP",2,0x8001)+ins("RET0",0,1)+ins("GGET",2,key+2)+ins("TGETS",2,512+key+3)
                   +ins("CALL",2,513)+ins("MOV",3+a,1)+ins("TGETS",3,256+key+4)
                   +ins("TGETS",4+a,512+key+5)+ins("IST",0,4+a)+ins("JMP",5+a,0x8001)
                   +ins("TNEW",4+a,0)+ins("CALL",3,259))
            assert nop==9 and instructions[-4:]==ins("RET0",0,1)
            instructions=instructions[:-4]+extra+instructions[-4:]
            head=head[:2]+bytes([max(head[2],7+a)])+head[3:]
            counts[mode]+=1
        if mode == "adventure_label" and index == 13:
            assert nop==527 and pack(b"ONE_SHOP") in pool
            reg=instructions[404*4+1]
            # 将已接通的商城入口显示在原主城导航，沿用原点击控制器。
            instructions=instructions[:404*4]+ins("KPRI",reg,2)+ins("JMP",reg,0x8007)+instructions[406*4:]
            counts["blind_entry"]+=1
        if mode == "rebirth_confirm" and index == 1:
            # 保留原候选为空的反馈。在扣费前确认当前预览与旧存档能识别的觉醒材料。
            key = len(pool)
            source_ui = Path(__file__).with_name("rebirth-confirm.lua").read_text("utf-8-sig").encode()
            pool = [pack(source_ui), pack(b"loadstring")] + pool
            cb, arg = 2, 2 + first_argument
            assert nop == 25 and instructions[17*4:18*4] == ins("UGET",cb,0)
            assert instructions[23*4:24*4] == ins("CALL",cb,259)
            instructions = (instructions[:17*4] + ins("GGET",cb,key) + ins("KSTR",arg,key+1) + ins("CALL",cb,514)
                            + ins("UGET",arg,0) + ins("TGETS",arg,arg*256+6)
                            + ins("UGET",arg+1,0) + ins("TGETS",arg+1,(arg+1)*256+2)
                            + ins("MOV",arg+2,1) + ins("CALL",cb,260) + instructions[24*4:])
            head = head[:2] + bytes((max(head[2],arg+3),)) + head[3:]
            counts[mode] += 1
        if mode == "fusion_supply" and index == 21:
            # 保留原需求预览、返回和合体按钮。说明与合体器图标分别绑定本地补给确认。
            key = len(pool)
            source_ui = Path(__file__).with_name("fusion-supply.lua").read_text("utf-8-sig").encode()
            extra = [b"loadstring", b"local freeOnly = false\n" + source_ui, b"local freeOnly = true\n" + source_ui]
            pool = [pack(value) for value in reversed(extra)] + pool
            cb = 4 + first_argument
            assert nop == 174 and instructions[138*4:139*4] == ins("UGET", cb, 13)
            assert instructions[139*4:140*4] == ins("CALL", 2, 260)
            def callback(script_key):
                return ins("GGET",cb,key) + ins("KSTR",cb+first_argument,script_key) + ins("CALL",cb,514)
            # 解释按钮替换原回调；紧接着给同页原 Item 对象绑定免费申领回调。
            intro = instructions[134*4:137*4]
            bind = ins("CALL",2,260)
            instructions = (instructions[:138*4] + callback(key+1) + bind + intro
                            + ins("KSTR",cb-1,8) + callback(key+2) + bind + instructions[140*4:])
            head = head[:2] + bytes((max(head[2],cb+first_argument+1),)) + head[3:]
            counts[mode] += 1
        if mode == "maze_board" and index in (0, 2, 13):
            base = len(pool)
            if index == 0:
                # 免费次数用尽后原按钮购买一次，成交后再次点击掷骰。
                assert nop == 6 and pool[-2] == pack(b"maze_throw")
                keys = [b"data", b"cur_throw_num", b"buy_throw"]
                alternate = instructions[:12] + ins("KSTR", first_argument+1, base+2) + instructions[16:]
                instructions = (ins("UGET",0,0) + ins("TGETS",0,base) + ins("TGETS",0,base+1)
                                + ins("KSHORT",1,1) + ins("ISLE",1,0) + ins("JMP",2,0x8006)
                                + alternate + instructions)
            elif index == 2:
                assert nop == 24 and pool[-7] == pack(b"add_integral")
                keys = ["每日免费%s次。次数用尽可用200钻购买一次普通骰，购买后再点掷骰。遥控骰每次500钻，每日最多购买或使用遥控骰合计3次。每走一格得%s分，到终点另得5分并进入下一层，超出的点数舍弃。停在宝箱格可免费开一次，继续移动会放弃未开宝箱。积分达标后，可用钻石购买本周商店商品。".encode()]
                instructions = instructions[:8] + ins("KSTR",first_argument,base) + instructions[24:]
            else:
                assert nop == 153 and instructions[39*4:40*4] == ins("ADDVV",1,1)
                keys = []
                cap = ins("KSHORT",2,23) + ins("ISLE",1,2) + ins("JMP",2,0x8001) + ins("MOV",1,2)
                instructions = instructions[:40*4] + cap + instructions[40*4:]
            pool = [pack(k) for k in reversed(keys)] + pool
            counts[mode] += 1
        if mode == "rune_wallet" and index == 2:
            assert nop==13 and pool[-2]==pack(b"add")
            pool[-2]=pack(b"set")
            counts[mode]+=1
        if mode == "maze_model" and index == 8:
            assert nop == 13 and pool[-5] == pack(b"buy_throw_callback")
            base = len(pool)
            pool = [pack(k) for k in (b"get_maze_map",b"Request",b"Network")] + pool
            instructions = (instructions[:20] + ins("JMP",2,0x8004) + ins("GGET",1,base)
                            + ins("TGETS",1,(1<<8)|(base+1)) + ins("KSTR",1+first_argument,base+2)
                            + ins("CALL",1,258) + instructions[-4:])
            counts[mode] += 1
        if mode == "maze_ctrl" and index == 1:
            # C10028 回来前的 S10005 留存了断线时尚未打开的宝箱。
            assert nop == 9 and pool[-1] == pack(b"view")
            base = len(pool)
            pool = [pack(b"move_end"),pack(b"brocast")] + pool
            instructions = (instructions[:-4] + instructions[:8] + ins("MOV",1+first_argument,1)
                            + ins("TGETS",1,(1<<8)|base) + ins("KSTR",2+first_argument,base+1)
                            + ins("CALL",1,259) + instructions[-4:])
            counts[mode] += 1
        if mode == "offline_operate" and index == 0:
            # 只提前开放已接入服务的免费领券和月度狂欢。其余原档保持原条件。
            assert nop == 245 and instructions[-4:] == ins("RET1", 0, 2)
            key = len(pool)
            pool = [pack(b"serverOpenDate")] + pool
            override = b""
            for activity in (10051, 10089, 20002):
                override += (ins("KSHORT",1,activity) + ins("TGETV",1,1)
                             + ins("KSHORT",2,0) + ins("TSETS",2,(1<<8)|key))
            instructions = instructions[:-4] + override + instructions[-4:]
            counts[mode] += 1
        if mode == "daily_free_view" and index in (1, 7):
            # 领取或跨日后的 S10014 主动重查面板，原按钮状态随本地日历刷新。
            assert pool.count(pack(b"sc_pay")) == 1
            pool = [pack(b"s2c_voucher_dayfree_data") if k == pack(b"sc_pay") else k for k in pool]
            assert nop == (67 if index == 1 else 20)
            chunk = (Path(__file__).with_name("local-coupon-wallet.lua").read_bytes() if index == 1
                     else b"local v=...; if v.localCouponClose then v.localCouponClose(); v.localCouponClose=nil end")
            key = len(pool)
            pool = [pack(chunk),pack(b"loadstring")] + pool
            arg = 1 + first_argument
            instructions = (instructions[:-4] + ins("GGET",1,key) + ins("KSTR",arg,key+1) + ins("CALL",1,514)
                            + ins("UGET",arg,1) + ins("CALL",1,258) + instructions[-4:])
            counts[mode] += 1
        if mode == "daily_free_view" and index == 2:
            assert nop == 23 and pool[-10] == pack(b"award")
            # 原界面可以在首个 C10423 回复前绘制，此时没有第一行奖励。
            instructions = (instructions[:19*4] + ins("IST",0,4) + ins("JMP",5,0x8001)
                            + ins("RET0",0,1) + instructions[19*4:])
            counts[mode] += 1
        if mode == "monthly_global" and index == 33:
            # 跳过原角色建号后第 30 日的广告窗口，仅保留前面按活动状态、起止时间的判断。
            assert nop == 165 and instructions[131*4:132*4] == ins("JMP",5,0x801f)
            instructions = instructions[:130*4] + ins("JMP",5,0x8020) + instructions[131*4:]
            counts[mode] += 1
        if mode == "monthly_model" and index == 0:
            assert nop == 5 and pool[-3] == pack(b"get_eo_data")
            key = len(pool)
            pool = [pack(b"ask_mission")] + pool
            instructions = instructions[:8] + ins("KSTR", first_argument, key) + instructions[12:16] + instructions
            counts[mode] += 1
        if mode == "monthly_view" and index == 0:
            assert nop == 36 and pool[-9] == pack(b"do_watch")
            # 原按钮信息来自所点击的奖励行。已领和未达成的行只显示说明。
            base = len(pool)
            names = [b"Network", b"Request", b"get_award", b"idx", b"id", b"get_eo_data",
                     "请先完成今日签到，再领取下一档奖励。".encode()]
            pool = [pack(name) for name in reversed(names)] + pool
            argument = 3 + first_argument
            call = ins("GGET",3,base) + ins("TGETS",3,(3<<8)|(base+1))
            submit = (call + ins("KSTR",argument,base+2) + ins("TNEW",argument+1,1)
                      + ins("TGETS",argument+2,(2<<8)|(base+4))
                      + ins("TSETS",argument+2,((argument+1)<<8)|(base+3)) + ins("CALL",3,259)
                      + call + ins("KSTR",argument,base+5) + ins("CALL",3,258) + ins("RET0",0,1))
            notice = (ins("GGET",3,3) + ins("TGETS",3,(3<<8)|4)
                      + ins("KSTR",argument,base+6) + ins("CALL",3,258) + ins("RET0",0,1))
            instructions = (instructions[:8] + ins("KSHORT",4,1) + ins("ISNEV",3,4)
                            + ins("JMP",4,0x8000+len(submit)//4) + submit + notice)
            head = bytes((head[0],head[1],max(head[2],argument+3),head[3]))
            counts[mode] += 1
        if mode == "monthly_view" and index == 5:
            assert nop == 41 and pool[-7] == pack(b"get_lang")
            key = len(pool)
            pool = [pack("每日签到后可领取下一档奖励。漏签不清空进度，每天限一档，本轮共三十档。".encode())] + pool
            instructions = instructions[:8] + ins("KSTR",1,key) + instructions[14*4:]
            counts[mode] += 1
        if mode == "equipment_rating_view" and index == 2:
            # 底包缺 rating_proto。标题与分档读取服务端同一份配置，保留原图标和进度条。
            assert nop == 58 and pool[-2] == pack(b"rating_proto") and pool[-19] == pack(b"value")
            rules = json.loads(RATING_RULES.read_text("utf-8"))
            thresholds = rules["gradeMinimums"]
            assert len(thresholds) == 6 and thresholds == sorted(set(thresholds))
            assert thresholds[0] == 0 and thresholds[-1] == 100 and len(rules["titles"]) == 3
            titles = Template([None] + [title.encode() for title in rules["titles"]], {})
            grades = Template([sum(percent >= limit for limit in thresholds) for percent in range(101)], {})
            title_key, grade_key = len(pool), len(pool) + 1
            pool = [grades, titles] + pool
            instructions = (instructions[:5*4] + ins("TDUP", 4, title_key) + ins("TGETS", 5, (3<<8)|2)
                            + ins("TGETV", 4, (4<<8)|5) + instructions[9*4:23*4]
                            + ins("MOV", 6, 4) + instructions[27*4:29*4] + ins("TDUP", 6, grade_key)
                            + ins("TGETS", 7, (3<<8)|18) + ins("TGETV", 6, (6<<8)|7) + instructions[30*4:])
            counts[mode] += 1
        if mode == "suit_sale" and index == 7:
            assert nop == 95 and pool[-33] == pack(b"sale") and instructions[-4:] == ins("RET0",0,1)
            chunk = Path(__file__).with_name("sell-suit-material.lua").read_bytes()
            key = len(pool)
            pool = [pack(chunk), pack(b"loadstring")] + pool
            arg = 4 + first_argument
            instructions = (instructions[:-4] + ins("GGET",4,key) + ins("KSTR",arg,key+1)
                            + ins("CALL",4,514) + ins("UGET",arg,2) + ins("MOV",arg+1,0)
                            + ins("CALL",4,259) + instructions[-4:])
            counts[mode] += 1
        if mode == "equipment_rating_entry" and index == 15:
            # 使用原改名确认按钮的皮肤和字体，在改名笔右侧提供当前角色的邮件道具入口。
            mail_button = """
local view = ...
local buttons = view:findobj("Btns")
local function action(name, title, x, y, width, onClick)
    if buttons.transform:Find(name) then return end
    local button = utils.inst(buttons, view:findobj("View/ChangeName/Rename"))
    button.name = name
    button:SetActive(true)
    local path = "Btns/" .. name
    local rect = view:findrect(path)
    rect.anchorMin = Vector2.New(0.5, 0.5)
    rect.anchorMax = Vector2.New(0.5, 0.5)
    rect.pivot = Vector2.New(0.5, 0.5)
    rect.localScale = Vector3.one
    rect.sizeDelta = Vector2.New(width, 50)
    rect.anchoredPosition = Vector2.New(x, y)
    view:findobj(path .. "/Text"):SetActive(false)
    local caption = utils.inst(button, view:findobj("View/Language/List/1/Text"))
    caption.name = "Caption"
    caption:SetActive(true)
    local captionRect = view:findrect(path .. "/Caption")
    captionRect.anchorMin = Vector2.New(0, 0)
    captionRect.anchorMax = Vector2.New(1, 1)
    captionRect.pivot = Vector2.New(0.5, 0.5)
    captionRect.localScale = Vector3.one
    captionRect.sizeDelta = Vector2.New(0, 0)
    captionRect.anchoredPosition = Vector2.New(0, 0)
    local label = view:findtext(path .. "/Caption")
    label.fontSize = 23
    label.raycastTarget = false
    label.text = title
    utils.add_click_event(button, onClick)
end
action("LocalEscapeMap", "脱离卡图", 235, 405, 116, function()
    view:brocast("click_close")
    Network.Request("mapJump", {id=990001})
end)
action("LocalItemMail", "发物品", 249, 344, 88, function()
    local role = chr_model.get_chr()
    if role and role.id then
        UnityEngine.Application.OpenURL("pokemon-local://item-mail?role=" .. tostring(role.id))
    end
end)
action("LocalRestoreRole", "重进角色", 200, -136, 128, function()
    msg_popup.show("从本机存档重新载入当前角色和场景？", "重进角色", 2, function(ok)
        local role = chr_model.get_chr()
        if ok and role and role.id then
            view:brocast("click_close")
            Network.Request("relogin", {sid=role.svrid or 1, uid=role.id})
        end
    end)
end)
action("LocalStamina", "体力补给", -200, -136, 128, function()
    require("module/navi/buythired_popup").show()
end)
-- 四个按钮共用原礼包兑换一行，保持从左到右的操作顺序。
local gift = view:findrect("Btns/Gifts")
gift.anchorMin = Vector2.New(0.5, 0.5)
gift.anchorMax = Vector2.New(0.5, 0.5)
gift.pivot = Vector2.New(0.5, 0.5)
gift.sizeDelta = Vector2.New(138, 50)
gift.anchoredPosition = Vector2.New(-225, gift.anchoredPosition.y)
local cleanupBusy = false
local cleanupRequest = 0
local function cleanup(kind, title, warning)
    if cleanupBusy then return end
    msg_popup.show(warning .. "，此操作不可恢复，确定继续？", title, 2, function(ok)
        if not ok or cleanupBusy then return end
        cleanupBusy = true
        cleanupRequest = cleanupRequest + 1
        Network.Request("local_profile_clear", {kind=kind, requestId=tostring(os.time()) .. ":" .. tostring({}) .. ":" .. cleanupRequest})
    end)
end
local function cleaned(response)
    if not cleanupBusy then return end
    cleanupBusy = false
    if not response or response.errno ~= 0 then
        label_popup.show("清除失败，请重试")
        return
    end
    local role = chr_model.get_chr()
    view:brocast("click_close")
    label_popup.show("已清除，正在重新载入角色")
    if role and role.id then Network.Request("relogin", {sid=role.svrid or 1, uid=role.id}) end
end
-- 模块只保留最新页面的监听，避免重复打开时多次重进。
if _G.LocalProfileCleanupListener then Network.RemoveListener("local_profile_clear", _G.LocalProfileCleanupListener) end
_G.LocalProfileCleanupListener = cleaned
Network.AddListener("local_profile_clear", cleaned)
action("LocalClearElves", "清除所有精灵", -75, gift.anchoredPosition.y, 142, function()
    cleanup(1, "清除所有精灵", "将删除当前角色全部精灵（含上阵、助阵、锁定精灵及其已穿戴装备）")
end)
action("LocalClearItems", "清除背包物品", 75, gift.anchoredPosition.y, 142, function()
    cleanup(2, "清除背包物品", "将删除当前角色背包物品、碎片、贴纸及未穿戴装备")
end)
action("LocalClearMail", "清除邮箱", 225, gift.anchoredPosition.y, 138, function()
    cleanup(3, "清除邮箱", "将删除当前角色全部邮件，未领取附件也会清除")
end)
local assess = view:findrect("Btns/Assess")
assess.anchoredPosition = Vector2.New(230, 289)
assess.localScale = Vector3.New(0.56, 0.56, 1)
view:findrect("Btns/Assess/Text").anchoredPosition = Vector2.New(88, 0)
"""
            assert nop==66 and pool[-19]==pack(b"Btns/ChangeName") and instructions[-4:]==ins("RET0",0,1)
            key=len(pool)
            pool=[pack(mail_button.encode()),pack(b"loadstring")]+pool
            instructions=(instructions[:-4]+ins("GGET",0,key)+ins("KSTR",first_argument,key+1)
                          +ins("CALL",0,514)+ins("UGET",first_argument,1)+ins("CALL",0,258)+instructions[-4:])
            counts["profile_item_mail"]+=1
        if mode == "equipment_rating_entry" and index == 2:
            # 原资料页已为 Btns/Assess 绑定点击；原初始化直接传入 false。
            arg = 2 + first_argument
            assert nop == 168 and pool[-35] == pack(b"Btns/Assess")
            assert instructions[123*4:124*4] == ins("KPRI", arg, 1)
            level_key = len(pool)
            pool = [pack(b"level")] + pool
            unlock = json.loads(RATING_RULES.read_text("utf-8"))["unlockLevel"]
            instructions = (instructions[:123*4] + ins("TGETS", 5, level_key) + ins("KSHORT", 6, unlock)
                            + ins("KPRI", arg, 2) + ins("ISLE", 6, 5) + ins("JMP", 5, 0x8001)
                            + ins("KPRI", arg, 1) + instructions[124*4:])
            counts[mode] += 1
        if mode == "turntable_main" and index == 0:
            assert nop == 27 and pool[-8] == pack(b"explain_popup")
            help_text = ("每周一零点开启新一轮。每日首抽免费，可再抽两次，每次300钻。每抽得10积分。"
                         "觉醒石40%、神奇的糖果30%、银钥匙18%、红色万能突破卡10%、金色万能突破卡2%。"
                         "兑换消耗可用积分，周榜保留累计积分。前三名在周末结算后通过邮件领取对应档奖励。")
            instructions = instructions[:5*4] + ins("KSTR", 2, len(pool)) + instructions[21*4:]
            pool = [pack(help_text.encode())] + pool
            counts[mode] += 1
        if mode == "turntable_main" and index == 12:
            assert nop == 137 and instructions[5*4:6*4] == ins("TGETB", 1, 257)
            # C10022.pool 和 C10024.pool_num 均为今日余次，抽后立即刷新单抽的实际费用。
            cost = (ins("TGETS", 2, (1<<8)|6) + ins("KSHORT", 3, 3) +
                    ins("ISNEV", 2, 3) + ins("JMP", 2, 0x8002) +
                    ins("KSTR", 1, len(pool)) + ins("JMP", 2, 0x8007) +
                    ins("KSHORT", 3, 0) + ins("ISNEV", 2, 3) + ins("JMP", 2, 0x8002) +
                    ins("KSTR", 1, len(pool)+1) + ins("JMP", 2, 0x8002) +
                    ins("TGETS", 1, (1<<8)|3) + ins("TGETB", 1, (1<<8)|2))
            instructions = instructions[:4*4] + cost + instructions[6*4:]
            pool = [pack("已用尽".encode()), pack("免费".encode())] + pool
            counts[mode] += 1
        if mode == "turntable_main" and index == 21:
            assert nop == 178 and pool[-39] == pack(b"Button/Ten") and pool[-18] == pack(b"SetActive")
            # 十连超过每日总额度。仅保留真实可用的单抽入口。
            hide = (ins("UGET", 1, 3) + ins("MOV", 1+first_argument, 1) +
                    ins("TGETS", 1, (1<<8)|8) + ins("KSTR", 2+first_argument, 38) +
                    ins("CALL", 1, (2<<8)|3) + ins("MOV", 1+first_argument, 1) +
                    ins("TGETS", 1, (1<<8)|17) + ins("KPRI", 2+first_argument, 1) +
                    ins("CALL", 1, (1<<8)|3))
            instructions = instructions[:147*4] + hide + instructions[153*4:]
            counts[mode] += 1
        if mode == "turntable_rank" and index == 3:
            assert nop == 76 and pool[-5] == pack(b"gfes_model")
            description = "每抽累计10分，同分按先达到该分的时间排序。每周一零点结算上周，前三名奖励发送到角色邮件。"
            instructions = instructions[:2*4] + ins("KSTR", 1, len(pool)) + instructions[6*4:]
            pool = [pack(description.encode())] + pool
            counts[mode] += 1
        if mode == "turntable_rank" and index == 5:
            assert nop == 28 and instructions[11*4:12*4] == ins("TGETS", 1, (1<<8)|4)
            rank = (ins("KSHORT", 2, 0) + ins("ISNEV", 1, 2) + ins("JMP", 2, 0x8002) +
                    ins("KSTR", 4, len(pool)) + ins("JMP", 2, 0x8004) +
                    ins("KSTR", 4, len(pool)+1) + ins("MOV", 5, 1) +
                    ins("KSTR", 6, len(pool)+2) + ins("CAT", 4, (4<<8)|6) +
                    ins("UGET", 2, 0) + ins("TGETS", 2, (2<<8)|5) + ins("TSETS", 4, (2<<8)|6))
            instructions = instructions[:12*4] + rank + instructions[25*4:]
            pool = [pack("名".encode()), pack("第".encode()), pack("未上榜".encode())] + pool
            head = head[:2] + bytes([max(7,head[2])]) + head[3:]
            counts[mode] += 1
        if mode == "turntable_language":
            assert index == 0 and pool[0].array[6] == "奖池奖励:".encode()
            pool[0].array[6] = "今日余次:".encode()
            pool[0].array[19] = "积分:".encode()
            counts[mode] += 1
        if mode=="rocket_match_counter" and index==21:
            # 原匹配倒计时保留，次数由 C332 原子写入并通过 S457 下发。
            assert nop==49 and instructions[20*4:21*4]==ins("ADDVN",1,256)
            instructions=instructions[:20*4]+ins("MOV",1,1)+instructions[21*4:]
            counts[mode]+=1
        if mode=="rocket_clock" and index==0:
            # 本期剩余活动时长来自 S457，保留原 chr_model 游戏钟。
            assert nop==60 and pool[-1]==pack(b"gpeakedness_model") and pool[-25]==pack(b"get_time")
            key=len(pool)
            pool=[pack(b"endutc")]+pool
            instructions=(instructions[:3*4]+ins("TGETS",5,key)+instructions[55*4:])
            counts[mode]+=1
        if mode == "passport_model" and index == 2:
            # 数据库行保留原奖励，按连接传入的当前赛季起止展示。离散领取记录按原档位还原。
            assert nop == 39 and pool[-8] == pack(b"config") and pool[-2] == pack(b"convert")
            open_key = len(pool)
            end_key, record_key = open_key + 1, open_key + 2
            pool = [pack(b"record"), pack(b"endtime"), pack(b"opentime")] + pool
            dates = b""
            for key in (open_key, end_key):
                dates += (ins("TGETS", 3, key) + ins("IST", 0, 3) + ins("JMP", 4, 0x8001)
                          + ins("JMP", 4, 0x8001) + ins("TSETS", 3, (2 << 8) | key))
            records = bytearray(instructions[5*4:17*4])
            records[(8-6)*4+2] = record_key
            records += ins("UGET", 2, 0) + ins("TSETS", 1, (2 << 8) | record_key)
            instructions = instructions[:36*4] + dates + records + instructions[36*4:]
            counts[mode] += 1
        if mode == "passport_config":
            for value in pool:
                if not isinstance(value, Template):
                    continue
                row = value.array
                if row == [None,50001,50002,50018,50004,50005]:
                    value.array = [task for task in row if task != 50018]
                    counts["passport_tasks"] += 1
                if row == [None,50011,50007,50008,50009,50010,50017]:
                    value.array = [None,50011,50007,50010,50017,50018]
                    counts["passport_tasks"] += 1
                if row == [None,50006,50013,50012,50014,50015]:
                    value.array = [None,50006,50013,50014,50015]
                    counts["passport_tasks"] += 1
                if len(row) == 50 and row[40:42] == [120,34136] and row[47:49] == [120,34135]:
                    row[40] = row[47] = 1
                    counts["passport_shop"] += 1
        if mode == "mail_elf_count":
            # 原邮件奖励渲染直接读取 elfinfo.n，聚合显示尚未领取的同种精灵。
            for i, value in enumerate(pool):
                if not isinstance(value, bytes) or b".elfinfo{" not in value:
                    continue
                reader = Reader(value)
                schema = reader.raw(reader.num()-5)
                match = re.search(rb"\.elfinfo\s*\{[^}]*?pcltype\s+34\s*:\s*integer[^\n]*", schema)
                assert match and reader.pos == len(value)
                schema=schema[:match.end()] + b"\n\t\tn 35 : integer" + schema[match.end():]
                boss=re.search(rb"\.worldboss_info\s*\{[^}]*?lucky_player\s+12\s*:\s*\*rankinginfo[^\n]*",schema)
                assert boss
                pool[i]=pack(schema[:boss.end()]+b"\n\t\tlocal_bought 13 : boolean"+schema[boss.end():])
                counts[mode] += 1
        if mode == "league_protocol":
            for i, value in enumerate(pool):
                if not isinstance(value,bytes) or b"passport 804 {" not in value:
                    continue
                reader = Reader(value)
                schema = reader.raw(reader.num()-5)
                match = re.search(rb"passport 804 \{\s*request\s*\{.*?convert\s+6\s*:\s*\*ipair[^\n]*", schema,re.S)
                assert match and reader.pos == len(value)
                pool[i] = pack(schema[:match.end()] + b"\n\t\topentime 7 : string\n\t\tendtime 8 : string" + schema[match.end():])
                counts["passport_wire"] += 1
                schema = Reader(pool[i])
                original = schema.raw(schema.num()-5)
                match = re.search(rb"sc_rocket\s+457\s*\{\s*request\s*\{.*?status\s+6\s*:\s*integer[^\n]*",original,re.S)
                assert match
                pool[i] = pack(original[:match.end()] + b"\n\t\tstartutc 7 : integer\n\t\tendutc 8 : integer" + original[match.end():])
                counts["rocket_wire"] += 1
            for i, value in enumerate(pool):
                if isinstance(value, bytes) and b"casino 462 {" in value:
                    reader = Reader(value)
                    schema = reader.raw(reader.num()-5)
                    m = re.search(rb"casino 462 \{\s*request\s*\{[^}]*?record\s+3\s*:\s*\*normalshop_limit_buy[^\n]*", schema)
                    assert m and reader.pos == len(value)
                    pool[i] = pack(schema[:m.end()] + b"\n\t\t\tactive 4 : integer" + schema[m.end():])
                if not isinstance(value,bytes) or b"league_replay 249 {" not in value:
                    continue
                reader = Reader(value)
                schema = reader.raw(reader.num()-5)
                assert reader.pos == len(value)
                for tag, number in ((b"league_replay",249),(b"elite_replay",250)):
                    pattern = (tag + b" " + str(number).encode() + rb" \{\s*request\s*\{(.*?)(\n\s*\}\s*response\s*\{)(.*?)(\n\s*\})")
                    m = re.search(pattern,schema,re.S)
                    assert m and re.search(rb"idx\s+3\s*:\s*integer",m[1]) and re.search(rb"right\s+6\s*:\s*formation_t",m[3])
                    schema = schema[:m.end(1)] + b"\n\t\t\tpage 4 : integer" + m[2] + m[3] + b"\n\t\t\treport 7 : string\n\t\t\tnextidx 8 : integer\n\t\t\tnextpage 9 : integer" + schema[m.start(4):]
                m = re.search(rb"get_exchange_bountyboss_info\s+10013\s*\{\s*request\s*\{\s*\}\s*response\s*\{.*?info\s+1\s*:\s*\*bountyboss_shop[^\n]*",schema,re.S)
                assert m, "缺少原悬赏兑换协议"
                schema = schema[:m.end()] + b"\n      wallet 2 : integer" + schema[m.end():]
                m = re.search(rb"xmastree_shop 386 \{\s*request\s*\{(.*?)(\n\s*\}\s*response\s*\{)(.*?)(\n\s*\})", schema, re.S)
                assert m and re.search(rb"idx\s+0 : integer", m[1]) and re.search(rb"nowcount\s+2 : integer", m[3])
                schema = (schema[:m.end(1)] + b"\n\t\t\tquote 1 : string" + m[2] + m[3]
                          + b"\n\t\t\tpreview 3 : string\n\t\t\tquote 4 : string" + schema[m.start(4):])
                assert b"local_fusion_supply" not in schema and not re.search(rb"(?m)^\s*\w+\s+10990\s*\{",schema)
                schema += b"\nlocal_fusion_supply 10990 {\n request {\n action 0 : integer\n }\n response {\n errno 0 : integer\n message 1 : string\n }\n}\n"
                assert b"local_stamina_exchange" not in schema and not re.search(rb"(?m)^\s*\w+\s+10992\s*\{",schema)
                schema += b"\nlocal_stamina_exchange 10992 {\n request {}\n response {\n errno 0 : integer\n }\n}\n"
                assert not re.search(rb"(?m)^\s*\w+\s+10991\s*\{",schema)
                schema += b"""
local_coupon_wallet 10991 {
 request {
  kind 0 : integer
  issue 1 : integer
  day 2 : integer
 }
 response {
  errno 0 : integer
  kind 1 : integer
  day 2 : integer
  uid 3 : string
  status 4 : integer
  name 5 : string
  redeem 6 : integer
  coins 7 : integer
  diamonds 8 : integer
 }
}
"""
                assert b"local_profile_clear" not in schema
                schema += b"\nlocal_profile_clear 10994 {\n request {\n kind 0 : integer\n requestId 1 : string\n }\n response {\n errno 0 : integer\n kind 1 : integer\n }\n}\n"
                schema = append_tablet_protocol(schema)
                pool[i] = pack(schema)
                counts[mode] += 1
        if (mode == "casino_global" and index in (0, 19, 20)
                or mode == "casino_resume" and index in (8, 12, 14)):
            head, pool, instructions = casino_resume(mode, index, head, pool, instructions, first_argument)
            counts[mode] += 1
        if mode == "christmas_confirm" and index == 5:
            assert nop == 33 and pool[-6] == pack(b"idx") and pool[-12] == pack(b"update_exchange")
            head, pool, instructions, leaf = christmas_confirm(head, pool, instructions, first_argument)
            patched += encode(len(leaf)) + leaf
            counts[mode] += 1
        if mode == "league_report_view" and index == 11:
            assert nop == 27 and pool[-1] == pack(b"eparser") and pool[0] == pack(b"REPORT")
            head, pool, instructions, leaf = league_report_view(head,pool,first_argument)
            patched += encode(len(leaf)) + leaf
            counts[mode] += 1
        if mode == "adventure_end":
            # 原 data_mgr 会把 copy2 的所有键合入 copy；30001 只供末关后的导航和挑战页读取。
            assert index == 0 and nop == 19679 and len(pool) == 8435
            assert instructions[-12:] == ins("KSHORT", 2, 30000) + ins("TSETV", 1, 2) + ins("RET1", 0, 2)
            assert pool[2].fields[b"nextid"] == 30001 and pool[1].array == [None, 51]
            assert pool[-3] == pack(b"mapget") and pool[-5] == pack(b"bossget")
            terminal = dict(pool[2].fields)
            terminal.update({b"nextid": 0, b"scean": "第20000关（已通关）".encode()})
            del terminal[b"monster1"]
            append = (ins("TDUP", 1, len(pool)) + ins("TDUP", 2, len(pool) - 2) + ins("TSETS", 2, 258)
                      + ins("TNEW", 2, 0) + ins("TSETS", 2, 260) + ins("KSHORT", 2, 30001) + ins("TSETV", 1, 2))
            pool = [Template([], terminal)] + pool
            instructions = instructions[:-4] + append + instructions[-4:]
            counts[mode] += 1
        if mode == "release_preview" and index == 9:
            # 取 get_recycle_res 返回的第 5 项努力值，原代码误从输入精灵的数字键取值。
            cursor = 23 * 4
            assert nop == 125 and instructions[cursor:cursor+4] == ins("TGETB", 12, (10 << 8) | 5)
            instructions = instructions[:cursor] + ins("TGETB", 12, (11 << 8) | 5) + instructions[cursor+4:]
            counts[mode] += 1
        if mode == "adventure_label" and index == 25:
            # 普通关卡显示原关号，终点改读专门的已通关标题。
            assert nop == 67 and len(pool) == 23 and instructions[21*4:22*4] == ins("TSETS", 4, (3<<8)|8)
            title = (ins("TGETS", 5, (1<<8)|len(pool)) + ins("KSHORT", 6, 0)
                     + ins("ISNEV", 5, 6) + ins("JMP", 5, 0x8001) + ins("TGETS", 4, (1<<8)|(len(pool)+1)))
            pool = [pack(b"scean"), pack(b"nextid")] + pool
            instructions = instructions[:21*4] + title + instructions[21*4:]
            counts[mode] += 1
        if mode == "elite_prizes":
            assert index == 0 and nop == 44
            by_id = list(reversed(pool))
            for stage, start in enumerate((0,13,20)):
                assert by_id[start].fields[b"guess_cost"] == 10000
                by_id[start].fields[b"guess_cost"] = 5000
                awards = [obj for obj in by_id[start+1:] if isinstance(obj, Template)][:6]
                assert awards[0].array[1:3] == [3,(64072,64088,64114)[stage]]
                assert awards[4].array[:4] == [None,103,0,200000]
                assert awards[5].array == [None,*range(50021,50028)]
                for award, values in zip(awards,([1,21059,1],[1,21068,1],[],[],[103,0,7500])):
                    award.array = [None,*values]
            counts[mode] += 1
        if mode == "league_prizes":
            assert index == 0 and nop == 50
            by_id = list(reversed(pool))
            for stage, start in enumerate((0, 15, 23)):
                assert by_id[start].fields[b"groupnum"] == (32,16,8)[stage]
                by_id[start].fields[b"groupnum"] = 2
                arrays = [obj for obj in by_id[start+1:] if isinstance(obj, Template)][:7]
                for rank, obj in enumerate(arrays):
                    assert obj.array[1:3] == [3, (64092,64072,64088)[stage]]
                    obj.array = [None] if rank not in (1,2,3,4) else [
                        None,1,(21059,21068,21068,21063)[rank-1],2 if rank == 4 else 1]
            counts[mode] += 1
        if mode == "league_prize_view" and index in (1,6):
            if index == 1:
                # 四档都按实际名次显示，取消原列表末行的额外参与奖覆盖。
                assert nop == 99 and instructions[17*4:18*4] == ins("ISNEV",1,4)
                instructions = instructions[:15*4] + instructions[32*4:]
            else:
                # 此期奖品均为道具，打开无精灵广告图的原布局。
                assert nop == 106 and instructions[13*4:14*4] == ins("TSETS",2,(1<<8)|2)
                instructions = (instructions[:6*4] + ins("KSHORT",2,4) + instructions[13*4:14*4]
                                + ins("KPRI",2,0) + instructions[29*4:])
            counts[mode] += 1
        if mode == "new_year_fund" and index == 11:
            assert nop == 140 and pool[-3] == pack(b"opentime") and pool[-5] == pack(b"endtime")
            # 原页面将配置内 2024 年的两个固定日期拼接为文字；本地按北京自然周循环。
            instructions = instructions[:2*4] + ins("KSTR", 2, len(pool)) + instructions[12*4:]
            pool = [pack("每周一 00:00 至周日 23:59（北京时间）".encode())] + pool
            counts[mode] += 1
        if (mode, index) in (("feast_week_model", 1), ("feast_week_view", 2)):
            # 保留原倒计时与领奖刷新，替换固定历史日期，按北京时间周一零点切换。
            model = mode == "feast_week_model"
            assert pack(b"denglutanchuang_proto") in pool and pack(b"endtime") in pool
            assert nop == (52 if model else 105)
            now, start, end, offset, week = (4, 2, 3, 5, 6) if model else (3, 4, 5, 6, 7)
            vv = lambda op, dest, left, right: ins(op, dest, (left << 8) | right)
            # 同时对齐起点和下周起点；倒计时包含本周的最后一秒。
            clock = b"".join((ins("KNUM", offset, nnumbers), ins("KNUM", week, nnumbers + 1),
                              vv("ADDVV", start, now, offset), vv("MODVV", end, start, week),
                              vv("SUBVV", start, start, end), vv("ADDVV", end, start, week),
                              vv("SUBVV", start, start, offset), vv("SUBVV", end, end, offset)))
            numbers += encode(288000 << 1) + encode(604800 << 1)
            nnumbers += 2
            if model:
                # 原判断在周一零点跳过计时。半开区间 start <= now < end 与服务端一致。
                assert instructions[27*4:28*4] == bytes((1, start, now, 0))
                assert instructions[29*4:30*4] == bytes((3, now, end, 0))
                instructions = (instructions[:27*4] + bytes((3, start, now, 0)) + instructions[28*4:29*4]
                                + bytes((1, now, end, 0)) + instructions[30*4:])
                instructions = instructions[:8*4] + instructions[16*4:19*4] + clock + instructions[19*4:]
            else:
                instructions = instructions[:33*4] + clock + instructions[41*4:]
            counts[mode] += 1
        if mode == "league_clock" and index == 8:
            pool, instructions = league_clock(pool, instructions, first_argument)
            counts[mode] += 1
        if mode == "elite_clock" and index == 16:
            pool, instructions = league_clock(pool, instructions, first_argument, elite=True)
            counts[mode] += 1
        if mode == "secret_instant" and index == 3:
            # 超出原三档后沿用最后一档；原回退误取了外层配置表的长度。
            assert nop == 94 and pool[-12] == pack(b"data6")
            code = bytearray(instructions)
            for pos, register, table_register in ((40, 6, 5), (50, 7, 6)):
                assert code[pos*4:(pos+1)*4] == bytes((21, register, 3, 0))
                code[pos*4+2] = table_register
            instructions = bytes(code)
            counts[mode] += 1
        if mode == "secret_instant" and index == 5:
            # 根据随包结算配置开放四个任务，保留原普通怪与稀有怪筛选。
            assert nop == 114 and pool[-22] == pack(b"gdun_model") and pool[-25] == pack(b"grouptype")
            assert instructions[64*4:65*4] == ins("GGET", 1, 21)
            key = len(pool)
            rules = APP_RESOURCES / "secret/encounters.json"
            tasks = json.loads(rules.read_text("utf-8"))["tasks"]
            eligible = {int(task): 0 for task, row in tasks.items() if "monsterDrop" in row}
            assert eligible and all(task > 0 for task in eligible)
            pool = [Template([], eligible), pack(b"get_current")] + pool
            reward = [ins("GGET", 2, 21), ins("TGETS", 2, (2<<8)|key), ins("CALL", 2, 513),
                      ins("ISF", 0, 2), ins("JMP", 3, 0x8003), ins("TGETS", 2, (2<<8)|14),
                      ins("TDUP", 3, key+1), ins("TGETV", 2, (3<<8)|2)]
            arg = 5 + first_argument
            assert instructions[92*4:93*4] == bytes((0, arg, 2, 0))
            instructions = (instructions[:64*4] + b"".join(reward) + instructions[74*4:92*4]
                            + bytes((4, arg, 2, 0)) + instructions[93*4:])
            counts[mode] += 1
        if mode == "book_cache" and index in (0, 2):
            # 两个累加函数持有与查询函数共享的表；无入参时换成空表。
            assert nop == 16 and head[1] == 1 and head[3] == 1 and pool[-1] == pack(b"id")
            reset = (ins("IST", 0, 0) + ins("JMP", 1, 0x8003) + ins("TNEW", 1, 0)
                     + ins("USETV", 0, 1) + ins("RET0", 0, 1))
            instructions = reset + instructions
            counts[mode] += 1
        if mode == "book_cache" and index == 7:
            assert nop == 28 and head[3] == 3 and pool[-1] == pack(b"type")
            reset = b""
            for upvalue in (0, 2):
                reset += ins("UGET", 1, upvalue) + ins("KPRI", 1 + first_argument, 0) + ins("CALL", 1, 258)
            reset += ins("RET0", 0, 1)
            instructions = ins("IST", 0, 0) + ins("JMP", 1, 0x8000 + len(reset)//4) + reset + instructions
            counts[mode] += 1
        if mode == "book_snapshot" and index == 2:
            # S17 是全量背包。每次先清空极巨书和残卷索引，再按原函数分类。
            assert nop == 56 and pool[-12] == pack(b"glarge_model") and pool[-13] == pack(b"add_book")
            reset = (ins("GGET", 0, 11) + ins("TGETS", 0, 12) + ins("KPRI", first_argument, 0)
                     + ins("CALL", 0, 258))
            instructions = reset + instructions
            counts[mode] += 1
        if (mode, index) in (("mine_rewards", 10), ("mine_rare_rewards", 11)):
            assert nnumbers == 1 and numbers == encode(5 << 1)
            numbers = encode(2 << 1)
            counts[mode] += 1
        if (mode, index) in (("mine_rewards", 8), ("mine_rare_rewards", 9)):
            assert nop == (38 if mode == "mine_rewards" else 77)
            assert pool[-4] == pack(b"level") and pool[-10] == pack(b"probability")
            # 每半小时按所在矿区阶段结算。保留原金币图标、奖励格、矿名和稀有面板布局。
            quota = len(pool)
            pool = [pack(b"n"), Template([], {b"type": 103, b"sid": 0})] + pool
            values = [ins("TDUP", 5, quota), ins("TGETS", 6, 3), ins("KSHORT", 7, 60),
                      ins("KSHORT", 8, 1000), ins("ISLE", 6, 7), ins("JMP", 9, 0x8005),
                      ins("KSHORT", 8, 1500), ins("KSHORT", 7, 100), ins("ISLE", 6, 7),
                      ins("JMP", 9, 0x8001), ins("KSHORT", 8, 2500), ins("TSETS", 8, (5<<8) | (quota+1))]
            cleanup = instructions[37*4:42*4] if mode == "mine_rare_rewards" else b""
            instructions = instructions[:13*4] + b"".join(values) + instructions[26*4:36*4] + cleanup + instructions[-4:]
            counts[mode] += 1
        if mode == "bounty_wallet" and index == 6:
            assert nop == 64 and pool[-20] == pack(b"gbag_model") and pool[-23] == pack(b"number")
            # 本地消费余额由商店应答提供，避免把原悬赏令背包数量作积分。
            instructions = (instructions[:52*4] + ins("UGET",6,0) + ins("TGETS",6,6<<8)
                            + ins("TGETS",6,(6<<8)|13) + ins("TGETS",8,(6<<8)|len(pool))
                            + instructions[59*4:61*4] + instructions[62*4:])
            pool = [pack(b"wallet")] + pool
            counts[mode] += 1
        if mode == "bounty_count" and index == 9:
            assert nop == 40 and instructions[24*4:25*4] == ins("TGETS",3,(3<<8)|15)
            instructions = instructions[:19*4] + ins("KSHORT",3,5) + instructions[25*4:]
            counts[mode] += 1
        if mode == "bounty_language":
            assert index == 0 and pool[0].array[21] == "累计挑战悬赏BOSS %s 次".encode()
            pool[0].array[15] = "今日剩余挑战：%s次".encode()
            pool[0].array[21] = "本周累计达到 %s 积分".encode()
            pool[0].array[26] = "我的可用积分：".encode()
            counts[mode] += 1
        if mode == "league_rules":
            for obj in pool:
                if isinstance(obj, Template) and b"data1" in obj.fields:
                    value = obj.fields[b"data1"]
                    if isinstance(value, bytes) and "所有的矿洞均可产出进化精灵的属性石".encode() in value:
                        obj.fields[b"data1"] = ("矿洞每天北京时间 04:00 重置。每人每天可采集八小时，累计金币最多 40,000。"
                            "\n\n每满 30 分钟，三个世界的矿区分别产出 1,000、1,500 和 2,500 金币。"
                            "\n\n每天可掠夺标有 NPC 的矿位两次，胜利获得 1,500 金币。").encode()
                        counts["mine_rules"] += 1
                    if isinstance(value, bytes) and "联盟大会由联盟会赛和精英竞赛组成".encode() in value:
                        obj.fields[b"data1"] = ("本机联赛共八席，由你的第一队与七名 NPC 参加。赛季从建角日开始，每七个自然日循环。\n\n"
                            "第一日报名并锁定阵容。第二至四日进行小组单循环，各组前两名晋级。第五日交叉半决赛，第六日决赛，第七日结算。\n\n"
                            "完成至少三场可领取名次奖励。点击分组可查看本机赛果，离线期间的对局会在下次进入时补齐。\n\n"
                            "上届实际八名选手晋级下周精英赛。首日可同步第一队，第三、五、七日分别举行八进四、半决赛和决赛。"
                            "完成三场的选手可领取最终一档奖励。淘汰后可竞猜后续场次，每次和每日限五千金币，命中返还七千五百金币（含本金）。").encode()
                        counts[mode] += 1
        if mode == "gear_advance" and index == 31:
            # 进阶表只有三条附属性加成，第四、五条保留原值并显示增加零。
            assert len(instructions) == 64 * 4 and pool[-5] == pack(b"attribute")
            assert instructions[15*4:16*4] == bytes((56, 9, 9, 2))
            code = bytearray(instructions)
            for position, old, new in ((9, 45, 48), (54, -45, -48)):
                offset = position * 4 + 2
                assert struct.unpack_from("<H", code, offset)[0] == 0x8000 + old
                struct.pack_into("<H", code, offset, 0x8000 + new)
            guard = ins("IST", 0, 9) + ins("JMP", 10, 0x8001) + ins("KSHORT", 9, 0)
            instructions = bytes(code[:16*4]) + guard + bytes(code[16*4:])
            counts[mode] += 1
        if mode == "gift_currency" and index == 31:
            # 两页均使用原人民币档位，仅旧商城总价前误用了美元符号。
            assert len(instructions) == 181 * 4 and pool[-10] == pack(b"$")
            assert pool[-9] == pack(b"price") and pack(b"chongzhi_proto") in pool
            pool[-10] = pack("¥".encode())
            counts[mode] += 1
        if mode == "recovery_price" and index == 0:
            # 金币副本找回第一档按满额金币的十分之一收费，弹窗直接显示所需金币。
            dst = 9 + first_argument
            assert len(instructions) == 58 * 4 and pool[-4] == pack(b"msg")
            assert instructions[46*4:47*4] == ins("TGETS", dst, (7 << 8) | 16)
            price = b"".join((ins("TGETS", 5, (2 << 8) | 3), ins("TGETS", 6, (5 << 8) | 4),
                              ins("KSHORT", 7, 1100), ins("ISNEV", 6, 7), ins("JMP", 7, 0x8004),
                              ins("TGETS", dst, (5 << 8) | len(pool)), ins("TGETS", dst, (dst << 8) | 16),
                              ins("KSHORT", 7, 10), ins("DIVVV", dst, (dst << 8) | 7)))
            pool = [pack(b"reward")] + pool
            instructions = instructions[:47*4] + price + instructions[47*4:]
            counts[mode] += 1
        if mode == "luckybag_time" and index == 8:
            # 原页读取 2024 年固定档期，改用原活动日历已格式化的本轮起止日期。
            assert len(instructions) == 146 * 4 and pool[-1] == pack(b"gfes_model")
            assert instructions[20*4:21*4] == ins("TGETS", 4 + first_argument, (2 << 8) | 9)
            assert instructions[26*4:27*4] == ins("CAT", 4, (4 << 8) | 6)
            clock = b"".join((ins("GGET", 4, 0), ins("TGETS", 4, (4 << 8) | len(pool)),
                              ins("KSHORT", 4 + first_argument, 10074), ins("CALL", 4, 514)))
            pool = [pack(b"get_operate_time")] + pool
            instructions = instructions[:19*4] + clock + instructions[27*4:]
            counts[mode] += 1
        if mode == "carnival_time" and index == 20:
            # 把固定建角日换成本轮 S10002 的活动截止时间，保留原界面倒计时和图文。
            assert len(instructions) == 81 * 4 and len(pool) == 24
            assert instructions[20*4:21*4] == ins("TGETS", 2 + first_argument, 9)
            idx = len(pool)
            clock = b"".join((ins("GGET", 2, idx), ins("TGETS", 2, (2 << 8) | (idx + 1)),
                              ins("KSHORT", 2 + first_argument, 10027), ins("CALL", 2, 514),
                              ins("MOV", 1, 2)))
            pool = [pack(b"get_operate_endtime"), pack(b"gfes_model")] + pool
            instructions = instructions[:14*4] + clock + instructions[40*4:]
            counts["carnival_time"] += 1
        if mode == "daily_counts" and index == 12:
            pool, instructions = show_daily_sweeps(pool,instructions,first_argument)
            counts["daily_counts"] += 1
        if mode == "space_sync" and index == 3:
            assert len(instructions) == 3 * 4 and instructions[-4:] == ins("RET0",0,1)
            instructions = instructions[-4:]
            counts[mode] += 1
        if mode == "daily_sync" and index == 10:
            assert len(instructions) == 15 * 4 and instructions[-4:] == ins("RET0",0,1)
            instructions = instructions[-4:]
            counts["daily_sync"] += 1
        if mode == "tower_sync" and index in (3, 6):
            # S430 已带实际失败次数及下一楼层，战斗关闭回调保留导航事件。
            assert len(instructions) == {3:6, 6:21}[index] * 4 and instructions[-4:] == ins("RET0",0,1)
            instructions = instructions[-4:]
            counts["tower_sync"] += 1
        if mode == "overview":
            for obj in pool:
                if isinstance(obj, Template) and obj.fields.get(b"lock") in (1164, 1194):
                    assert obj.fields[b"name"] == {1164: 9046, 1194: 9110}[obj.fields[b"lock"]]
                    assert obj.fields[b"pos"] == 0
                    obj.fields[b"pos"] = 2
                    counts["overview"] += 1
        if mode == "variable": adjust_variables(pool, counts)
        if mode == "vip": adjust_limits(pool, counts)
        if mode == "jjcreward": pool, instructions = adjust_prizes(pool, instructions, counts)
        if mode == "sports" and index == 3:
            pool, instructions = hide_unused_box(pool, instructions, first_argument)
            counts["hidden"] += 1
        if mode == "sports" and index == 5:
            instructions, numbers = refresh_three(instructions, numbers)
            counts["three_tiers"] += 1
        if mode == "solo" and index == 9:
            pool, instructions = show_solo_wallet(pool, instructions, first_argument)
            counts["solo_wallet"] += 1
        if mode == "sports_tabs" and index == 9:
            pool, instructions = hide_live_match_tab(pool, instructions, first_argument)
            counts["hide_live_match"] += 1
        if mode == "pandect_model" and index in (38, 39):
            instructions = guard_missing_overview(instructions)
            counts["red_nil"] += 1
        if mode == "pandect_view" and index == 1:
            pool, instructions = open_arena_overview(pool, instructions, first_argument)
            counts["arena_entry"] += 1
        if mode == "pandect_view" and index == 11:
            pool, instructions = hide_unpublished_overview(pool, instructions)
            counts["visible_rows"] += 1
        index += 1
        next_proto = (head + encode(len(pool)) + encode(nnumbers) + encode(len(instructions) // 4)
                      + instructions + variables + b"".join(map(serial_constant, pool)) + numbers)
        patched += encode(len(next_proto)) + next_proto
    assert cur.pos == len(source)
    patched.append(0)
    if mode == "variable":
        assert len(counts) == 9 and counts["家园遗迹购次"] == 2 and counts["百关钻石闯关"] == 1 and sum(counts.values()) == 10, counts
    elif mode == "map_unlock":
        assert counts == {70043: 1, 70044: 1, 70045: 1, 70046: 1}, counts
    elif mode in ("speed_state", "pcl_unlock"):
        assert counts == {mode:1}, counts
    elif mode in ("skill_upgrade_panel","skill_upgrade_rows","skill_upgrade_submit","skill_upgrade_sync"):
        assert counts == {mode:5 if mode == "skill_upgrade_rows" else 1}, counts
    elif mode in ("dungeon_hook_row","dungeon_hook_model","dungeon_hook_panel"):
        assert counts == {mode:3 if mode == "dungeon_hook_panel" else 1}, counts
    elif mode == "relic_counts":
        assert counts == {mode:2}, counts
    elif mode in ("vip_sign","stamina_popup"):
        assert counts == {mode: 1 if mode=="vip_sign" else 3}, counts
    elif mode == "race_grid":
        assert counts == {mode:2}, counts
    elif mode == "equipment_rating_entry":
        assert counts=={mode:1,"profile_item_mail":1}, counts
    elif mode in ("rebirth_confirm","fusion_supply","bounty_count","bounty_wallet","bounty_language","equipment_rating_view","christmas_confirm","free_sign_prompt","dnf_clock"):
        assert counts == {mode:1}, counts
    elif mode in ("rune_wallet","suit_sale"):
        assert counts=={mode:1}, counts
    elif mode in ("maze_board","maze_ctrl","maze_model"):
        assert counts == {mode: 3 if mode == "maze_board" else 1}, counts
    elif mode in ("offline_operate", "daily_free_view"):
        assert counts == {mode: 3 if mode == "daily_free_view" else 1}, counts
    elif mode in ("monthly_view", "monthly_model", "monthly_global"):
        assert counts == {mode: 2 if mode == "monthly_view" else 1}, counts
    elif mode in ("casino_global", "casino_resume"):
        assert counts == {mode:3}, counts
    elif mode in ("turntable_main", "turntable_rank", "turntable_language"):
        assert counts == {mode: {"turntable_main":3,"turntable_rank":2,"turntable_language":1}[mode]}, counts
    elif mode in ("world_regular","world_master"):
        assert counts=={mode:(3 if mode=="world_regular" else 2)},counts
    elif mode == "overview":
        assert counts == {mode:2}, counts
    elif mode in ("new_year_fund", "feast_week_model", "feast_week_view", "daily_counts", "daily_sync", "space_sync", "carnival_time", "luckybag_time", "recovery_price", "gift_currency", "gear_advance"):
        assert counts == {mode:1}, counts
    elif mode == "league_rules":
        assert counts == {"league_rules":1, "mine_rules":2}, counts
    elif mode in ("book_cache", "book_snapshot"):
        assert counts == {mode: 3 if mode == "book_cache" else 1}, counts
    elif mode in ("mine_rewards", "mine_rare_rewards", "secret_instant"):
        assert counts == {mode:2}, counts
    elif mode == "mail_elf_count":
        assert counts == {mode:1}, counts
    elif mode == "league_protocol":
        assert counts == {"league_protocol":1, "passport_wire":1, "rocket_wire":1}, counts
    elif mode == "adventure_label":
        assert counts=={mode:1,"blind_entry":1}, counts
    elif mode in ("blind_sales","blind_payment","blind_help","blind_global","blind_screen","blind_bank_capacity","pet_customize"):
        assert counts=={mode:{"blind_sales":3,"blind_screen":2}.get(mode,1)},counts
    elif mode == "passport_config":
        assert counts == {"passport_tasks":66, "passport_shop":1}, counts
    elif mode in ("rocket_match_counter", "rocket_clock", "passport_model", "league_prizes", "league_clock", "elite_clock", "elite_prizes", "league_report_view", "adventure_end", "adventure_label", "release_preview"):
        assert counts == {mode:1}, counts
    elif mode in ("tower_sync","league_prize_view"):
        assert counts == {mode:2}, counts
    elif mode == "vip":
        assert len(counts) == 5 and min(counts.values()) > 0 and len(set(counts.values())) == 1, counts
    elif mode == "jjcreward":
        assert counts == {"weekly":1}, counts
    elif mode == "solo":
        assert counts == {"solo_wallet":1}, counts
    elif mode == "sports_tabs":
        assert counts == {"hide_live_match":1}, counts
    elif mode == "pandect_model":
        assert counts == {"red_nil": 2}, counts
    elif mode == "pandect_view":
        assert counts == {"visible_rows": 1, "arena_entry": 1}, counts
    else:
        assert counts == {"hidden":1,"three_tiers":1}, counts
    return bytes(patched), counts


def ins(name, a, d):
    return struct.pack("<I", OP[name] | (a << 8) | (d << 16))

def casino_resume(mode, index, head, pool, code, first_argument):
    # 已付底分的牌桌可在低余额或凌晨恢复。新开牌桌仍接受原等级、时间和余额校验。
    base = len(pool)
    keys = [b"ghomegameble_model", b"active"]
    read = lambda a: ins("GGET", a, base) + ins("TGETS", a, (a<<8)|(base+1))
    if mode == "casino_global" and index == 0:
        assert len(code) == 20*4 and pool[-2] == pack(b"box")
        code = (ins("GGET", 1, base) + ins("TGETS", 2, base+1)
                + ins("TSETS", 2, (1<<8)|(base+1)) + code)
    elif mode == "casino_global" and index == 19:
        assert len(code) == 35*4 and pool[-3] == pack(b"hour")
        code = read(1) + ins("ISF", 0, 1) + ins("JMP", 1, 0x8002) + ins("KPRI", 1, 1) + ins("RET1", 1, 2) + code
    elif mode == "casino_global":
        assert len(code) == 41*4 and pool[-8] == pack(b"condition")
        code = code[:18*4] + read(5) + ins("IST", 0, 5) + ins("JMP", 5, 0x8000+17) + code[18*4:]
    elif index == 8:
        assert len(code) >= 60*4 and pool[-10] == pack(b"condition")
        code = code[:20*4] + read(6) + ins("ISEQV", 6, 2) + ins("JMP", 6, 0x8000+16) + code[20*4:]
    elif index == 12:
        # 重进已经爆牌的牌局，在发牌动画结束时保留原自动结算流程。
        assert len(code) == 25*4 and pool[-6] == pack(b"compute")
        keys = [b"casino_settle"]
        tail = [ins("KSHORT", 1, 21), ins("ISLE", 0, 1), ins("JMP", 1, 0x8004),
                ins("UGET", 1, 2), ins("TGETS", 1, (1<<8)|4),
                ins("TGETS", 1, (1<<8)|base), ins("CALL", 1, 257)]
        code = code[:-4] + b"".join(tail) + code[-4:]
    else:
        # 原发牌循环按庄家两张发起，追加玩家断线前已要到的第三至第五张。
        assert len(code) == 45*4 and pool[-3] == pack(b"player")
        keys = []
        added = [ins("KSHORT", 4, 3), ins("TGETS", 5, 2), ins("LEN", 5, 5), ins("KSHORT", 6, 1),
                 ins("FORI", 4, 0x8009), ins("TGETS", 8, 2), ins("TGETV", 8, (8<<8)|7),
                 ins("TDUP", 9, 3), ins("TSETS", 8, (9<<8)|4), ins("TSETS", 7, (9<<8)|5),
                 ins("LEN", 8, 1), ins("ADDVN", 8, (8<<8)|0), ins("TSETV", 9, (1<<8)|8),
                 ins("FORL", 4, 0x8000-9)]
        code = code[:37*4] + b"".join(added) + code[37*4:]
    return head, [pack(k) for k in reversed(keys)] + pool, code


def christmas_confirm(head, pool, original, first_argument):
    # 原兑换点击仅传 idx。收到预览时不更新限购，确认回调才提交该预览的唯一凭据。
    arg = 3 + first_argument
    child_keys = [b"idx", b"quote", b"Network", b"Request", b"xmastree_shop"]
    child_code = [ins("IST", 0, 0), ins("JMP", 1, 0x8001), ins("RET0", 0, 1), ins("UGET", 1, 0),
                  ins("TNEW", 2, 0), ins("TGETS", 3, (1<<8)|0), ins("TSETS", 3, (2<<8)|0),
                  ins("TGETS", 3, (1<<8)|1), ins("TSETS", 3, (2<<8)|1), ins("GGET", 3, 2),
                  ins("TGETS", 3, (3<<8)|3), ins("KSTR", arg, 4), ins("MOV", arg+1, 2),
                  ins("CALL", 3, 259), ins("RET0", 0, 1)]
    child = (bytes((0,1,8,1)) + encode(len(child_keys)) + encode(0) + encode(len(child_code))
             + b"".join(child_code) + struct.pack("<H", 0xc000) + b"".join(pack(k) for k in reversed(child_keys)))
    base = len(pool)
    extra = [b"preview", b"msg_popup", b"show", "确认精灵兑换".encode()]
    show = [ins("GGET", 3, base+1), ins("TGETS", 3, (3<<8)|(base+2)), ins("MOV", arg, 1),
            ins("KSTR", arg+1, base+3), ins("KSHORT", arg+2, 2), ins("FNEW", arg+3, base+4),
            ins("CALL", 3, 261), ins("UCLO", 0, 0x8000), ins("RET0", 0, 1)]
    check = [ins("TGETS", 1, base), ins("ISF", 0, 1), ins("JMP", 2, 0x8000+len(show))]
    head = bytes((head[0]|1, head[1], max(head[2],12), head[3]))
    return head, [b"\x00"] + [pack(k) for k in reversed(extra)] + pool, b"".join(check+show) + original, child


def league_report_view(head, pool, first_argument):
    # 弹窗的确认回调保留原请求。点取消即关闭，点确定才请求下一页或下一场。
    arg = 3 + first_argument
    leaf_pool = [b"type", b"league_replay", b"elite_replay", b"Network", b"Request"]
    leaf_code = [ins("IST",0,0), ins("JMP",1,0x8001), ins("RET0",0,1),
                 ins("UGET",1,0), ins("TGETS",2,1<<8), ins("KSHORT",3,1), ins("ISNEV",2,3),
                 ins("JMP",3,0x8002), ins("KSTR",2,1), ins("JMP",3,0x8001), ins("KSTR",2,2),
                 ins("GGET",3,3), ins("TGETS",3,(3<<8)|4), ins("MOV",arg,2), ins("MOV",arg+1,1),
                 ins("CALL",3,259), ins("RET0",0,1)]
    leaf = (bytes((0,1,8,1)) + encode(len(leaf_pool)) + encode(0) + encode(len(leaf_code))
            + b"".join(leaf_code) + struct.pack("<H",0xc001) + b"".join(pack(v) for v in reversed(leaf_pool)))
    idx = len(pool)
    extra = [b"nextpage", b"page", b"nextidx", b"idx", b"msg_popup", b"show", b"report", "本地战报".encode()]
    instructions = [ins("GGET",2,0), ins("TGETS",2,(2<<8)|1), ins("TGETS",2+first_argument,2), ins("CALL",2,514),
                    ins("IST",0,2), ins("JMP",3,0x8001), ins("RET0",0,1),
                    ins("KPRI",10,0), ins("KSHORT",9,1), ins("TGETS",2,idx),
                    ins("ISF",0,2), ins("JMP",3,0x8005), ins("TSETS",2,(1<<8)|(idx+1)),
                    ins("TGETS",2,idx+2), ins("TSETS",2,(1<<8)|(idx+3)), ins("FNEW",10,idx+len(extra)),
                    ins("KSHORT",9,2), ins("GGET",3,idx+4), ins("TGETS",3,(3<<8)|(idx+5)),
                    ins("TGETS",arg,idx+6), ins("KSTR",arg+1,idx+7), ins("MOV",arg+2,9), ins("MOV",arg+3,10),
                    ins("CALL",3,261), ins("UCLO",1,0x8000), ins("RET0",0,1)]
    head = bytes((head[0]|1,2,12,head[3]))
    return head,[b"\x00"]+[pack(v) for v in reversed(extra)]+pool,b"".join(instructions),leaf

def league_clock(pool, code, first_argument, elite=False):
    # 原页每天 21:30 倒计时替换为与本机角色建角日相对的七日赛程。
    assert (pool[-1] == pack(b"time") and len(code) == 80 * 4 if elite
            else len(code) == 76 * 4 and pool[-10] == pack(b"chr_model"))
    phases = (("同步阵容","八强备赛","八进四","四强备赛","半决赛","决赛备赛","决赛与领奖") if elite
              else ("报名","小组赛","小组赛","小组赛","半决赛","决赛","领奖"))
    extra = [b"get_chr", b"createtime", b"math", b"floor", Template([None] + [
        ("%s · 第%d天 · %s" % ("精英赛" if elite else "本机联赛",day,label)).encode()
        for day,label in enumerate(phases,1)], {}),b"chr_model",b"get_time",b"time",b"text"]
    idx = len(pool)
    sequence = [ins("GGET",0,idx+5), ins("TGETS",0,idx+6), ins("CALL",0,513),
                ins("GGET",1,idx+5), ins("TGETS",1,(1<<8)|idx), ins("CALL",1,513),
                ins("TGETS",1,(1<<8)|(idx+1)), ins("KSHORT",2,28800),
                ins("KSHORT",3,1440), ins("KSHORT",4,60), ins("MULVV",3,(3<<8)|4)]
    for register in (0,1):
        arg = 5 + first_argument
        sequence.extend((ins("GGET",5,idx+2), ins("TGETS",5,(5<<8)|(idx+3)),
                         ins("ADDVV",arg,(register<<8)|2), ins("DIVVV",arg,(arg<<8)|3),
                         ins("CALL",5,514), ins("MOV",register,5)))
    sequence.extend((ins("SUBVV",0,1), ins("KSHORT",1,7), ins("MODVV",0,1), ins("KSHORT",1,1),
                     ins("ADDVV",0,1), ins("TDUP",1,idx+4), ins("TGETV",0,1<<8),
                     ins("UGET",1,0), ins("TGETS",1,(1<<8)|(idx+7)), ins("TSETS",0,(1<<8)|(idx+8)), ins("RET0",0,1)))
    return [v if isinstance(v,Template) else pack(v) for v in reversed(extra)] + pool, b"".join(sequence)


def show_daily_sweeps(pool, code, first_argument):
    # 在原挑战次数文本后显示同类扫荡余量。S415 的 6..10 项与 1..5 项分开计数。
    assert len(pool) == 18 and len(code) == 61 * 4
    anchor = 42 * 4
    assert code[anchor:anchor+4] == ins("TSETS",3,(2<<8)|10)
    arg = 4 + first_argument
    extra = [ins("GGET",4,4), ins("TGETS",4,(4<<8)|5), ins("UGET",arg,0),
             ins("TGETS",arg,(arg<<8)|6), ins("KSHORT",7,5), ins("ADDVV",arg,(arg<<8)|7),
             ins("CALL",4,514), ins("IST",0,4), ins("JMP",5,0x8001), ins("TNEW",4,0),
             ins("TGETS",5,(4<<8)|13), ins("IST",0,5), ins("JMP",6,0x8001), ins("KSHORT",5,0),
             ins("KSHORT",6,1), ins("SUBVV",5,(6<<8)|5), ins("KSTR",4,len(pool)), ins("CAT",3,(3<<8)|5)]
    return [pack("  扫荡：".encode())] + pool, code[:anchor] + b"".join(extra) + code[anchor:]

def hide_live_match_tab(pool, code, first_argument):
    # 隐藏实时匹配页并移除该页原点击回调。
    assert len(pool) == 25 and len(code) == 142 * 4
    assert code[84*4:85*4] == ins("CALL", 2, 259)
    assert code[82*4:83*4] == ins("TGETB", 4 if first_argument == 2 else 3,
                                 ((4 if first_argument == 2 else 3) << 8) | 4)
    hide = b"".join((
        ins("UGET", 2, 2),
        ins("TGETS", 2, (2 << 8) | 5),
        ins("TGETB", 2, (2 << 8) | 4),
        ins("MOV", 2 + first_argument, 2),
        ins("TGETS", 2, (2 << 8) | len(pool)),
        ins("KPRI", 3 + first_argument, 1),
        ins("CALL", 2, 259),
    ))
    return [pack(b"SetActive")] + pool, code[:78*4] + hide + code[85*4:]

def guard_missing_overview(code):
    # 对没有 S1101 状态的活动，红点返回 false。
    assert len(code) == 20 * 4 and code[9*4:10*4] == ins("TGETS", 2, (1 << 8) | 4)
    guard = b"".join((ins("IST", 0, 1), ins("JMP", 2, 0x8002),
                      ins("KPRI", 2, 1), ins("RET1", 2, 2)))
    return code[:9*4] + guard + code[9*4:]

def open_arena_overview(pool, code, first_argument):
    # 在原锁定检查之后分发卡片；狩猎可直接进入原模块，主城 NPC 入口同时保留。
    assert len(pool) == 24 and len(code) == 160 * 4
    assert code[5*4:6*4] == ins("TGETS", 3, (1 << 8) | 2)
    names = [b"module_mgr", b"close", b"modulename", b"pandect", b"open", b"sports", b"hunt", b"league"]
    idx = {name: len(pool) + i for i, name in enumerate(names)}
    arg = 4 + first_argument
    branches = []
    for card, target in ((1, b"sports"), (2, b"hunt"), (17, b"league")):
        actions = []
        for method, module in ((b"close", b"pandect"), (b"open", target)):
            actions.extend([ins("GGET", 4, idx[b"module_mgr"]),
                            ins("TGETS", 4, (4 << 8) | idx[method]),
                            ins("GGET", arg, idx[b"modulename"]),
                            ins("TGETS", arg, (arg << 8) | idx[module]),
                            ins("CALL", 4, 258)])
        actions.append(ins("RET0", 0, 1))
        branches.extend([ins("KSHORT", 4, card), ins("ISNEV", 3, 4),
                         ins("JMP", 4, 0x8000 + len(actions)), *actions])
    return ([pack(name) for name in reversed(names)] + pool,
            code[:6*4] + b"".join(branches) + code[6*4:])


def hide_unpublished_overview(pool, code):
    # 冒险概览中没有当地 S1101 状态的活动不加入列表，三页的红点均有布尔默认值。
    assert len(pool) == 27 and len(code) == 133 * 4
    assert code[26*4:27*4] == ins("JMP", 12, 0x800e)
    assert code[54*4:55*4] == ins("TNEW", 4, 0)
    code = (code[:26*4] + ins("JMP", 12, 0x8019) + code[27*4:54*4]
            + ins("TDUP", 4, len(pool)) + code[55*4:])
    return [Template([None, False, False, False], {})] + pool, code

def refresh_three(instructions, numbers):
    result = bytearray(instructions)
    # 原函数的刷新循环、当前档和最高档均以第四项为边界。
    for position, register in ((16,3),(77,9),(79,11)):
        start = (position - 1) * 4
        assert result[start:start+4] == ins("KSHORT",register,4)
        result[start:start+4] = ins("KSHORT",register,3)
    numeric = Reader(numbers)
    assert numeric.num() == 2  # LuaJIT 常数 1，编码为 2。
    second = numeric.pos
    assert numeric.num() == 8  # LuaJIT 常数 4。
    return bytes(result), numbers[:second] + encode(6) + numbers[numeric.pos:]

def hide_unused_box(pool, code, first_argument):
    # 初始化依然创建四个原生控件，在控件引用完整后隐藏第四个。
    assert len(pool) == 31
    assert code[-4:] == ins("RET0",0,1)
    pool = [pack(b"SetActive")] + pool
    hide = b"".join((
        ins("UGET",0,0),
        ins("TGETS",0,7),
        ins("TGETB",0,4),
        ins("TGETS",0,8),
        ins("MOV",first_argument,0),
        ins("TGETS",0,31),
        ins("KPRI",first_argument+1,1),
        ins("CALL",0,259),
    ))
    return pool, code[:-4] + hide + code[-4:]

def show_solo_wallet(pool, code, first_argument):
    # 单人面板的已记账积分来自当前模式的 arenainfo，而非通用角色钱包。
    assert len(pool) == 49
    start = (73 - 1) * 4
    destination = 4 + first_argument
    assert code[start:start+4] == ins("MOV", destination, 0)
    code = code[:start] + ins("TGETS", destination, (1 << 8) | len(pool)) + code[start+4:]
    return [pack(b"arena_point")] + pool, code

def patch_lua_bundle(source, unity, patches):
    def texts(bundle):
        return {path: text_bytes(obj.read()) for path,obj in bundle.container.items() if obj.type.name == "TextAsset"}

    def text_bytes(asset):
        script = asset.m_Script
        return script.encode("utf-8", "surrogateescape") if isinstance(script,str) else script

    bundle = unity.load(source)
    before = texts(bundle)
    counts = Counter()
    for filename, mode in patches:
        name, = [path for path in before if path.endswith("/"+filename)]
        asset = bundle.container[name].read()
        if mode == "activity_shortcuts":
            patched, changes = patch_activity_shortcuts(
                before[name], Reader, encode, constants, serial_constant, pack, ins)
        else:
            patched, changes = update_lua(before[name],mode)
        if filename == "gfes_model.lua.bytes":
            patched, removed = patch_activity_removal(
                patched, Reader, encode, constants, serial_constant, pack, ins)
            changes.update(removed)
        asset.m_Script = patched.decode("utf-8", "surrogateescape")
        asset.save()
        before[name] = patched
        counts.update(changes)
    saved = bundle.file.save(packer="lz4")
    after = texts(unity.load(saved))
    assert before == after, "替换本地玩法界面后，有其他原脚本发生变化"
    return saved, counts

def asset_overlay(origin, dest, aes, pad, unpad, unity):
    with origin.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    if sha != ORIGIN_SHA256:
        raise ValueError("原始 APK SHA-256 与当前可验证底包不符")
    with zipfile.ZipFile(origin) as base:
        metadata = base.read(METADATA)
        sample = base.read(ASSETS + "data_001.ab")[:16]
        literal = metadata[METADATA_LITERAL_OFFSET:METADATA_LITERAL_OFFSET+32]
        if not aes.new(literal, aes.MODE_ECB).decrypt(sample).startswith(b"UnityFS\0"):
            raise ValueError("底包的 Unity 容器标识无法验证")

        def decrypt(data):
            return unpad(aes.new(literal, aes.MODE_ECB).decrypt(data),16)

        def encrypt(data):
            return aes.new(literal, aes.MODE_ECB).encrypt(pad(data,16))

        manifest = decrypt(base.read(ASSETS + "manifest.ab"))
        records = [line.split("|") for line in manifest.decode().splitlines()]
        indexed = {row[0]:row for row in records}
        assert len(indexed) == len(records)
        updated = {}
        changed = {}

        def replace(name, transform):
            row = indexed[name]
            filename, offset, length = row[1], int(row[2]), int(row[3])
            part = updated.get(filename)
            if part is None:
                part = bytearray(base.read(ASSETS + filename))
                updated[filename] = part
            assert len(part) % 16 == 0 and offset % 16 == 0 and length % 16 == 0
            original = decrypt(part[offset:offset + length])
            replacement = transform(original)
            encoded = encrypt(replacement)
            assert decrypt(encoded) == replacement
            row[2:] = [str(len(part)), str(len(encoded))]
            part += encoded
            return replacement

        def replace_lua(name, mode, source):
            bundles = {
                "guild_build_unlimited": (("unionbuild_view.lua.bytes", "guild_unlimited_build"),
                                          ("union_view.lua.bytes", "tablet_npc")),
                "tablet_helper_bundle": (("ghelper.lua.bytes", "tablet_helper"),),
                "dungeon_hook": (("mopup_view1.lua.bytes","dungeon_hook_row"), ("mopuphook_view.lua.bytes","dungeon_hook_panel"),
                                 ("mopup_model.lua.bytes","dungeon_hook_model")),
                "speed_state": (("speed_reward_view.lua.bytes", "speed_state"),
                                ("speed_choose_view.lua.bytes", "unlimited_fast_view")),
                "unlimited_sweep": (("sweep_ctrl.lua.bytes", "unlimited_quicken_control"),
                                    ("sweep_view.lua.bytes", "unlimited_quicken_view")),
                "race_grid_bundle": (("race_aidance.lua.bytes","race_grid"),),
                "dnf_clock": (("dnf_main_view.lua.bytes", "dnf_clock"),),
                "pet_customize": (("petanimal_msg.lua.bytes","pet_customize"),),
                "blind_screen": (("oneshop_ctrl.lua.bytes","blind_screen"),
                                 ("oneshop_bag.lua.bytes","blind_bank_capacity")),
                "maze_panel": (("striveview1.lua.bytes","maze_board"), ("fesstrive_model.lua.bytes","maze_model"), ("fesstrive_ctrl.lua.bytes","maze_ctrl")),
                "casino_resume": (("homegameble_view.lua.bytes","casino_resume"),),
                "rocket_clock": (("peakedness_model.lua.bytes","rocket_clock"), ("peakedness_view.lua.bytes","rocket_match_counter")),
                "christmas_confirm": (("feschristmas_model.lua.bytes", "christmas_confirm"),),
                "monthly_carnival": (("fesadvertise_view.lua.bytes", "monthly_view"), ("fesadvertise_model.lua.bytes", "monthly_model")),
                "daily_free": (("everydaypoint_view.lua.bytes", "daily_free_view"),),
                "daily_voucher_labels": (("tehuilibaopoint_view.lua.bytes", "daily_voucher_labels"),),
                "equipment_rating_view": (("assess_view.lua.bytes", "equipment_rating_view"),),
                "equipment_rating_entry": (("playerinfo_view.lua.bytes", "equipment_rating_entry"),),
                "release_preview": (("recycle_sub1.lua.bytes", "release_preview"), ("recycle_sub2.lua.bytes", "rebirth_confirm"),
                                    ("skill_panel.lua.bytes", "skill_upgrade_panel"), ("skill_obj.lua.bytes", "skill_upgrade_rows"),
                                    ("skill_uplevel.lua.bytes", "skill_upgrade_submit")),
                "adventure_label": (("navi_view.lua.bytes", "adventure_label"),("buythired_popup.lua.bytes","stamina_popup")),
                "bounty": (("fesoffer_subview2.lua.bytes","bounty_wallet"),("fesoffer_model.lua.bytes","bounty_count")),
                "world_regular_popup": (("sjboss_popup.lua.bytes","world_regular"), ("suitshow_popup.lua.bytes","suit_sale")),
                "world_master_view": (("kfboss_view.lua.bytes","world_master"),),
                "league_protocol_bundle": (("proto.lua.bytes","league_protocol"),("typedef.lua.bytes","mail_elf_count")),
                "league_views": (("league_model.lua.bytes","league_report_view"), ("leagueview1.lua.bytes", "league_clock"), ("leagueview4.lua.bytes", "league_prize_view"),
                                 ("leagueview6.lua.bytes","elite_clock")),
                "sports": (("sports_box.lua.bytes", "sports"), ("sports_sub_view2.lua.bytes", "solo"),
                           ("sports_view.lua.bytes", "sports_tabs")),
                "pandect_model": (("gsmallgame_model.lua.bytes","relic_counts"), ("elf_model.lua.bytes","skill_upgrade_sync"), ("gshop_model.lua.bytes","rune_wallet"), ("goneshop_model.lua.bytes","blind_global"), ("ghomegameble_model.lua.bytes","casino_global"), ("gpandect_model.lua.bytes", "pandect_model"), ("gdailydup_model.lua.bytes", "daily_sync"),
                                  ("grisk_model.lua.bytes", "tower_sync"), ("gspace_model.lua.bytes", "space_sync"),
                                  ("gequip_model.lua.bytes", "gear_advance"), ("gfeast_model.lua.bytes", "feast_week_model"),
                                  ("gbag_model.lua.bytes", "book_snapshot"), ("glarge_model.lua.bytes", "book_cache"),
                                  ("gpremit_model.lua.bytes", "passport_model"), ("gfes_model.lua.bytes", "monthly_global")),
                "new_year_fund": (("fesnewyearfund_view.lua.bytes", "new_year_fund"),),
                "mine_views": (("mine_sub_view.lua.bytes", "mine_rewards"), ("mine_rare_view.lua.bytes", "mine_rare_rewards")),
                "fusion_supply_view": (("spritefush_view.lua.bytes", "fusion_supply"),),
                "secret_battle": (("secretarea_battle.lua.bytes", "secret_instant"),),
                "feast_week": (("feast_view.lua.bytes", "feast_week_view"),),
                "pandect_view": (("pandect_view.lua.bytes", "pandect_view"),),
                "hunt_time": (("oneshop.lua.bytes","blind_help"), ("hunt.lua.bytes", "hunt_time"), ("feskfturn.lua.bytes", "turntable_language"), ("fesoffer.lua.bytes","bounty_language"), ("fessign.lua.bytes", "free_sign_prompt")),
                "turntable_views": (("kfturnview1.lua.bytes", "turntable_main"), ("kfturnview2.lua.bytes", "turntable_rank")),
                "daily_counts": (("dailydup_subview.lua.bytes", "daily_counts"),),
                "carnival_time": (("fesrevel_view.lua.bytes", "carnival_time"),),
                "recovery_price": (("sub_view2.lua.bytes", "recovery_price"),),
                "luckybag_time": (("fesluckybag_view.lua.bytes", "luckybag_time"),),
                "gift_currency": (("charge_view.lua.bytes", "gift_currency"),),
            }
            result, counters = (patch_lua_bundle(source, unity, bundles[mode])
                                if mode in bundles else update_lua(source, mode))
            print(name, counters)
            return result

        for abi in (32,64):
            name = f"lua{abi}_manager.ab"
            changed[name] = replace(name, lambda source: patch_lua_bundle(
                source, unity, (("module_mgr.lua.bytes", "activity_shortcuts"),))[0])
            name = f"lua{abi}_module_sweep.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "unlimited_sweep", source))
            name = f"lua{abi}_module_mopup.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "dungeon_hook", source))
            for asset, mode in (("petanimal","pet_customize"), ("union","guild_build_unlimited"), ("unionrace","race_grid_bundle"), ("speed","speed_state"), ("dnf","dnf_clock"), ("oneshop","blind_screen"), ("fesstrive","maze_panel"), ("peakedness", "rocket_clock"), ("assess", "equipment_rating_view"), ("playerinfo", "equipment_rating_entry"),
                                ("feschristmas", "christmas_confirm"), ("homegameble","casino_resume"), ("tehuilibaopoint", "daily_voucher_labels"), ("fesadvertise", "monthly_carnival"), ("everydaypoint", "daily_free")):
                name = f"lua{abi}_module_{asset}.ab"
                changed[name] = replace(name, lambda source: replace_lua(name, mode, source))
            for filename, mode in (("map","map_unlock"), ("sign","vip_sign"), ("one","blind_sales"), ("chongzhi","blind_payment"), ("league","league_prizes"), ("promatch","elite_prizes"), ("word1","league_rules"), ("copy2","adventure_end"), ("passcard","passport_config"), ("operate", "offline_operate"), ("leading", "pcl_unlock")):
                name = f"lua{abi}_database_{filename}_proto.lua.bytes"
                changed[name] = replace(name, lambda source: replace_lua(name, mode, source))
            name = f"lua{abi}_module_league.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "league_views", source))
            name = f"lua{abi}_module_spritefush.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "fusion_supply_view", source))
            name = f"lua{abi}_module_secretarea.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "secret_battle", source))
            name = f"lua{abi}_module_feskfturn.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "turntable_views", source))
            name = f"lua{abi}_network.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "league_protocol_bundle", source))
            name = f"lua{abi}_module_fesoffer.ab"
            changed[name] = replace(name, lambda source: replace_lua(name, "bounty", source))
            for mode in ("variable","vip","jjcreward","sports","overview"):
                name = (f"lua{abi}_module_sports.ab" if mode == "sports"
                        else f"lua{abi}_database_{mode}_proto.lua.bytes")
                changed[name] = replace(name, lambda source: replace_lua(name, mode, source))
            for asset, mode in (("helper","tablet_helper_bundle"),("popup","world_regular_popup"),("module_kfboss","world_master_view"),("gmodel", "pandect_model"), ("module_feast", "feast_week"), ("module_mine", "mine_views"), ("module_fesnewyearfund", "new_year_fund"), ("module_navi", "adventure_label"), ("module_sprite", "release_preview"), ("module_pandect", "pandect_view"), ("languages_zh", "hunt_time"), ("module_dailydup", "daily_counts"), ("module_fesrevel", "carnival_time"), ("module_charge", "gift_currency"), ("module_fesluckybag", "luckybag_time"), ("module_equalize", "recovery_price")):
                name = f"lua{abi}_{asset}.ab"
                changed[name] = replace(name, lambda source: replace_lua(name, mode, source))

        listed = set()

        def update_version(source):
            rows = [line.split(b"|") for line in source.split(b"\n")]
            for row in rows:
                name = row[0].decode("ascii")
                if name in changed:
                    payload = changed[name]
                    row[1:] = [hashlib.md5(payload).hexdigest().encode("ascii"), str(len(payload)).encode("ascii")]
                    listed.add(name)
            return b"\n".join(b"|".join(row) for row in rows)

        for name in ("version.txt", "xversion.txt"):
            replace(name, update_version)
        if listed != changed.keys():
            raise ValueError(f"Unity 版本清单缺少替换条目：{sorted(changed.keys() - listed)}")
        endings = manifest[len(manifest.rstrip(b"\r\n")):]
        separator = b"\r\n" if b"\r\n" in manifest else b"\n"
        updated["manifest.ab"] = encrypt(separator.join("|".join(row).encode() for row in records) + endings)
    folder = dest / PurePosixPath(ASSETS)
    folder.mkdir(parents=True,exist_ok=True)
    for name,data in updated.items():
        print(ASSETS + name,hashlib.sha256(data).hexdigest())
        (folder/name).write_bytes(data)
    digest = dest / "assets" / "pokemon-local" / "unity-index.txt"
    digest.parent.mkdir(parents=True, exist_ok=True)
    digest.write_text(hashlib.sha256(updated["manifest.ab"]).hexdigest(), encoding="ascii")

def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--origin",type=Path,required=True,help="原 APK 路径")
    args.add_argument("--output",type=Path,required=True,help="APK 条目增量输出目录")
    args.add_argument("--python-libs",type=Path,help="已有 pycryptodome 与 UnityPy 依赖目录")
    ns = args.parse_args()
    if not ns.origin.is_file():
        raise ValueError(f"原始 APK 不存在：{ns.origin}")
    ns.output.mkdir(parents=True, exist_ok=True)
    if ns.python_libs:
        sys.path.insert(0,str(ns.python_libs.resolve()))
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad,unpad
    import UnityPy
    asset_overlay(ns.origin,ns.output,AES,pad,unpad,UnityPy)

if __name__ == "__main__":
    main()
