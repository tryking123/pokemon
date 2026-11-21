"""最小移植喵星联盟变形与特性动作；保留底包其他已有战斗逻辑。"""
from pathlib import Path
import importlib.util
import struct
import sys
import hashlib

EXPECTED={
 'formation':{
  2:('24b739920fc61a96a6d2e709b8991dc53e2b92fd9e873a8106ae0bbedc912319','3274d792e9973008c745b898f5b63ef8142408ebee0f5c282eb726130f5fa87e'),
  10:('ae2da7afea98c8dfa9f7a53bbc104c2be8541a9969caa3c19446167dfdba02eb','959248774b5a19528b7146b259adcf2055b4ad4edaf02aaa071b5ccfecc7ec75')},
 'stage':{
  2:('7646fed177193b49747501587dda4d8a8f19ea119394adfbaee5a2edbf63be94','5f8b674a17f9753a2108710a538e899d77d885601baab9de2a6cde5a98d5a471'),
  10:('88737b661bac45baee7e69db0da345b1b35224b16b0bed61a57530f6c08d15ef','ed6871fa007729d2f19899b7dec49a237e3a416d7099ada97a9b99f4f829a1fc')}
}

def verify_pair(name,flags,original,donor):
    """只接受本次已核对的底包、供体脚本及其对应架构。"""
    assert tuple(hashlib.sha256(x).hexdigest() for x in (original,donor))==EXPECTED[name][flags]

PATCH = Path(__file__).resolve().parents[1] / 'app端代码/local-patch'
sys.path.insert(0, str(PATCH))
spec = importlib.util.spec_from_file_location('arena_migration_helpers', PATCH/'patch-unity-arena.py')
arena = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = arena
spec.loader.exec_module(arena)

def parse(data):
    """读取已剥离调试信息的 LuaJIT 原型，保留数字池原始字节。"""
    r=arena.Reader(data)
    assert r.raw(4)==b'\x1bLJ\x02'
    flags=r.num();assert flags&2 and not flags&1
    prefix=data[:r.pos];protos=[]
    while size:=r.num():
        body=r.raw(size);p=arena.Reader(body);head=p.raw(4)
        ngc,nn,nins=p.num(),p.num(),p.num()
        code=p.raw(nins*4);uv=p.raw(head[3]*2)
        start=p.pos;arena.constants(p,ngc);pool=body[start:p.pos];numbers=body[p.pos:]
        protos.append(dict(body=body,head=head,ngc=ngc,nn=nn,code=code,uv=uv,pool=pool,numbers=numbers))
    assert r.pos==len(data)
    return prefix,flags,protos

def body(p):
    """按原字段顺序序列化一个原型。"""
    return p['head']+arena.encode(p['ngc'])+arena.encode(p['nn'])+arena.encode(len(p['code'])//4)+p['code']+p['uv']+p['pool']+p['numbers']

def ad(op,a,d):return struct.pack('<BBH',op,a,d)
def abc(op,a,b,c):return bytes([op,a,c,b])

def patch_formation(original, donor):
    """补入动作 81—88 和新版变形，返回字节码及校验说明。"""
    prefix,flags,old=parse(original);_,donorflags,src=parse(donor)
    assert flags==donorflags,(flags,donorflags)
    verify_pair('formation',flags,original,donor)
    assert len(old)==257 and len(src)==265
    assert old[3]['uv']==struct.pack('<4H',0xc000,0xc008,0x800c,0x800d)
    assert src[3]['uv']==old[3]['uv']+struct.pack('<H',0x800f)
    assert old[232]['uv']==src[231]['uv'], '动作71捕获的root局部寄存器不一致'
    # 这些动作不包含内嵌子原型，复制不会改变原有父子原型栈关系。
    extra=src[241:250]
    for p in [src[3],src[231],*extra]:
        rr=arena.Reader(p['pool']);pool=arena.constants(rr,p['ngc'])
        assert b'\0' not in pool,'不能直接复制带子原型的函数'
        for value in struct.unpack('<'+'H'*(len(p['uv'])//2),p['uv']):
            assert value&0x8000 and (value&0x3fff)<26, '捕获值不是已核对的root局部'
    root=dict(old[-1]);assert root['head'][2]==26
    final=root['code'][-4:];assert final[0]==76,'底包root不是单表返回'
    words=list(struct.unpack('<'+'I'*(len(root['code'])//4),root['code']))
    lastaction=[i for i,word in enumerate(words) if word&255==62 and word>>24==24 and (word>>16&255)==80]
    assert len(lastaction)==1
    at=(lastaction[0]+1)*4
    rr=arena.Reader(root['pool']);logical=list(reversed(arena.constants(rr,root['ngc'])))
    new_index=logical.index(arena.pack(b'new'));assert new_index==349
    suffix=b''.join(ad(51,26,root['ngc']+i)+abc(62,26,24,81+i) for i in range(8))
    # 此处 trigger 模块还在 R23 构造，稍后才赋给被各闭包捕获的 R15。
    suffix+=ad(51,26,root['ngc']+8)+ad(39,27,new_index)+abc(60,26,23,27)
    root['head']=root['head'][:2]+bytes([28])+root['head'][3:]
    root['code']=root['code'][:at]+suffix+root['code'][at:]
    root['pool']=b'\0'*9+root['pool'];root['ngc']+=9
    changed={3:src[3]['body'],232:src[231]['body']}
    result=bytearray(prefix)
    for i,p in enumerate(old[:-1]):
        raw=changed.get(i,p['body']);result+=arena.encode(len(raw))+raw
    for p in extra:result+=arena.encode(len(p['body']))+p['body']
    raw=body(root);result+=arena.encode(len(raw))+raw+b'\0'
    result=bytes(result);_,_,after=parse(result)
    assert len(after)==266
    for i,p in enumerate(old[:-1]):
        if i not in changed:assert after[i]['body']==p['body']
    assert after[-1]['code'][:at]+after[-1]['code'][at+len(suffix):]==old[-1]['code']
    assert src[251]['body']==old[243]['body'] and src[252]['body']==old[244]['body'] and src[253]['body']==old[245]['body']
    return result,{'oldPrototypes':257,'newPrototypes':266,'originalUnchangedPrototypes':254,'replacedHelper':3,'replacedAction71':232,'addedActions':list(range(81,89)),'addedTriggerNew':264,'dispatchJudgeHandleIdenticalToDonor':True,'requiresStageForceSkill':True,'rootInsertionAfterInstruction':at//4}

def patch_stage(original,donor):
    """仅补 force_skill 和受技能攻击后的事件27，保留其余stage原型。"""
    prefix,flags,old=parse(original);_,sf,src=parse(donor)
    assert flags==sf and len(old)==49 and len(src)==50
    verify_pair('stage',flags,original,donor)
    assert old[44]['uv']==src[45]['uv']
    assert old[44]['code'][:59*4]==src[45]['code'][:59*4]
    # 新perform仅增加17条事件27循环；原尾段的两个字符串常量索引后移2位。
    tail=bytearray(src[45]['code'][76*4:])
    for i in range(0,len(tail),4):
        if tail[i] in (57,61) and tail[i+2] in (12,13):tail[i+2]-=2
    assert bytes(tail)==old[44]['code'][59*4:]
    root=dict(old[-1]);assert root['head'][2]==36
    assert root['code'][-8:]==bytes([50,0,0,128,76,17,2,0])
    rr=arena.Reader(src[42]['pool']);assert b'\0' not in arena.constants(rr,src[42]['ngc'])
    assert src[42]['uv']==struct.pack('<4H',0xc01d,0xc012,0xc010,0xc002)
    extra=ad(51,36,root['ngc']+1)+ad(39,37,root['ngc'])+abc(60,36,17,37)
    root['code']=root['code'][:-8]+extra+root['code'][-8:]
    root['head']=root['head'][:2]+bytes([38])+root['head'][3:]
    root['pool']=b'\0'+arena.pack(b'force_skill')+root['pool'];root['ngc']+=2
    out=bytearray(prefix)
    for i,p in enumerate(old[:-1]):
        raw=src[45]['body'] if i==44 else p['body'];out+=arena.encode(len(raw))+raw
    raw=src[42]['body'];out+=arena.encode(len(raw))+raw
    raw=body(root);out+=arena.encode(len(raw))+raw+b'\0'
    out=bytes(out);_,_,after=parse(out);assert len(after)==50
    for i,p in enumerate(old[:-1]):
        if i!=44:assert after[i]['body']==p['body']
    return out,{'oldPrototypes':49,'newPrototypes':50,'originalUnchangedPrototypes':47,'replacedPerform':44,'addedForceSkill':48,'addedEvent':27}

if __name__=='__main__':
    import argparse,json
    ap=argparse.ArgumentParser();ap.add_argument('original',type=Path);ap.add_argument('donor',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--stage',action='store_true')
    ns=ap.parse_args();patched,proof=(patch_stage if ns.stage else patch_formation)(ns.original.read_bytes(),ns.donor.read_bytes());ns.output.write_bytes(patched);print(json.dumps(proof,ensure_ascii=False))
