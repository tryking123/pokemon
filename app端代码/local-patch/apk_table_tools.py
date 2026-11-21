"""只读提取 APK 数据表，并将新增行叠加到原 LuaJIT 表，不执行来源脚本。"""
from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
import hashlib,struct,json,re

class Reader:
    def __init__(self,data):self.data=data;self.pos=0
    def raw(self,n):
        out=self.data[self.pos:self.pos+n];assert len(out)==n;self.pos+=n;return out
    def num(self):
        n=shift=0
        while True:
            b=self.raw(1)[0];n|=(b&127)<<shift
            if b<128:return n
            shift+=7

def uleb(n):
    out=bytearray()
    while n>=128:out.append((n&127)|128);n>>=7
    out.append(n);return bytes(out)

def signed(n):return struct.unpack('<i',struct.pack('<I',n&0xffffffff))[0]

OPS='ISLT ISGE ISLE ISGT ISEQV ISNEV ISEQS ISNES ISEQN ISNEN ISEQP ISNEP ISTC ISFC IST ISF ISTYPE ISNUM MOV NOT UNM LEN ADDVN SUBVN MULVN DIVVN MODVN ADDNV SUBNV MULNV DIVNV MODNV ADDVV SUBVV MULVV DIVVV MODVV POW CAT KSTR KCDATA KSHORT KNUM KPRI KNIL UGET USETV USETS USETN USETP UCLO FNEW TNEW TDUP GGET GSET TGETV TGETS TGETB TGETR TSETV TSETS TSETB TSETM TSETR CALLM CALL CALLMT CALLT ITERC ITERN VARG ISNEXT RETM RET RET0 RET1'.split()

def decode_table(raw):
    cur=Reader(raw);assert cur.raw(4)==b'\x1bLJ\x02';flags=cur.num();assert flags in (0,2,8,10)
    if not flags&2:cur.raw(cur.num())
    size=cur.num();p=Reader(cur.raw(size));head=p.raw(4);ng,nk,nb=p.num(),p.num(),p.num()
    debug=0 if flags&2 else p.num()
    if debug:p.num();p.num()
    code=[(OPS[o],a,b,c,(b<<8)+c) for o,a,c,b in struct.iter_unpack('BBBB',p.raw(nb*4))]
    assert not head[1] and not head[3]
    def value():
        t=p.num()
        if t>=5:return p.raw(t-5).decode('utf-8')
        if t==3:return signed(p.num())
        if t==4:return struct.unpack('<d',struct.pack('<II',p.num(),p.num()))[0]
        return (None,False,True)[t]
    gc=[]
    for _ in range(ng):
        kind=p.num()
        if kind==1:
            na,nh=p.num(),p.num();tab={}
            for i in range(na):
                v=value()
                if v is not None:tab[i]=v
            for i in range(nh):k,v=value(),value();tab[k]=v
            gc.append(tab)
        elif kind>=5:gc.append(p.raw(kind-5).decode('utf-8'))
        else:raise ValueError(f'不支持的数据表常量 {kind}')
    gc.reverse();nums=[];low=[]
    for _ in range(nk):
        v=p.num();n=v>>1;low.append(n);nums.append(struct.unpack('<d',struct.pack('<II',n,p.num()))[0] if v&1 else signed(n))
    assert p.pos+debug==len(p.data) and cur.raw(1)==b'\0' and cur.pos==len(raw)
    regs={};glob={}
    for op,a,b,c,d in code:
        if op=='TNEW':regs[a]={}
        elif op=='TDUP':regs[a]=gc[d].copy()
        elif op=='MOV':regs[a]=regs[d]
        elif op=='KSTR':regs[a]=gc[d]
        elif op=='KSHORT':regs[a]=d if d<32768 else d-65536
        elif op=='KNUM':regs[a]=nums[d]
        elif op=='KPRI':regs[a]=(None,False,True)[d]
        elif op=='UNM':regs[a]=-regs[d]
        elif op=='GSET':glob[gc[d]]=regs[a]
        elif op=='GGET':regs[a]=glob[gc[d]]
        elif op=='TSETV':regs[b][regs[c]]=regs[a]
        elif op=='TSETS':regs[b][gc[c]]=regs[a]
        elif op=='TSETB':regs[b][c]=regs[a]
        elif op=='TGETS':regs[a]=regs[b][gc[c]]
        elif op=='TGETV':regs[a]=regs[b][regs[c]]
        elif op=='TGETB':regs[a]=regs[b][c]
        elif op=='TSETM':
            for i in range(a,max(regs)+1):regs[a-1][low[d]+i-a]=regs[i]
        elif op=='RET1':return normalize(regs[a])
        elif op=='RET0':return normalize(glob)
        else:raise ValueError(f'不支持的数据表指令 {op}')
    raise ValueError('数据表没有返回值')

def normalize(v):return {str(k):normalize(x) for k,x in v.items()} if isinstance(v,dict) else v
def stable(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def sequence(v):return [v[k] for k in sorted(v,key=int)] if isinstance(v,dict) else []
def sha(b):return hashlib.sha256(b).hexdigest()
def file_sha(path):
    with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

class ApkAssets:
    def __init__(self,path,overlay=None):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import unpad,pad
        self.zip=ZipFile(path);self.cache={};self.changed={};self.overlay=Path(overlay) if overlay else None
        self.AES,self.unpad,self.pad=AES,unpad,pad
        if 'assets/ab.zip' in self.zip.namelist():
            self.key=None;self.database=ZipFile(BytesIO(self.zip.read('assets/ab.zip')))
            self.index={n:None for n in self.database.namelist()};self.index.update({n[10:]:None for n in self.zip.namelist() if n.startswith('assets/ab/')})
        else:
            self.key=self.zip.read('assets/bin/Data/Managed/Metadata/global-metadata.dat')[86192:86224]
            self.manifest=self.decrypt(self.physical('manifest.ab'));self.records=[line.split('|') for line in self.manifest.decode().splitlines()]
            self.index={r[0]:r for r in self.records}
    def physical(self,name):
        if name in self.cache:return self.cache[name]
        f=self.overlay/'assets/assets'/name if self.overlay else None
        self.cache[name]=f.read_bytes() if f and f.exists() else self.zip.read('assets/assets/'+name)
        return self.cache[name]
    def decrypt(self,b):return self.unpad(self.AES.new(self.key,self.AES.MODE_ECB).decrypt(b),16)
    def encrypt(self,b):return self.AES.new(self.key,self.AES.MODE_ECB).encrypt(self.pad(b,16))
    def read(self,name):
        if self.key:
            _,file,offset,size=self.index[name];return self.decrypt(self.physical(file)[int(offset):int(offset)+int(size)])
        return self.database.read(name) if name in self.database.namelist() else self.zip.read('assets/ab/'+name)
    def replace(self,name,data):
        assert self.key and self.overlay and name in self.index
        row=self.index[name];file=row[1];blob=self.physical(file);encoded=self.encrypt(data)
        row[2:]=[str(len(blob)),str(len(encoded))];self.cache[file]=blob+encoded;self.changed[name]=data
    def save(self):
        assert self.overlay
        changed=set(self.changed);listed=set()
        for name in ['version.txt','xversion.txt']:
            lines=self.read(name).splitlines(keepends=True);updated=[]
            for line in lines:
                ending=line[len(line.rstrip(b'\r\n')):];parts=line.rstrip(b'\r\n').split(b'|');logical=parts[0].decode()
                if logical in changed:
                    raw=self.changed[logical];parts[1:]=[hashlib.md5(raw).hexdigest().encode(),str(len(raw)).encode()];listed.add(logical)
                updated.append(b'|'.join(parts)+ending)
            self.replace(name,b''.join(updated))
        assert listed==changed,(listed^changed)
        ending=self.manifest[len(self.manifest.rstrip(b'\r\n')):];sep=b'\r\n' if b'\r\n' in self.manifest else b'\n'
        manifest=self.encrypt(sep.join('|'.join(row).encode() for row in self.records)+ending)
        folder=self.overlay/'assets/assets';folder.mkdir(parents=True,exist_ok=True)
        touched={self.index[name][1] for name in self.changed}
        for name in touched:(folder/name).write_bytes(self.cache[name])
        (folder/'manifest.ab').write_bytes(manifest)
        mark=self.overlay/'assets/pokemon-local/unity-index.txt';mark.parent.mkdir(parents=True,exist_ok=True);mark.write_text(sha(manifest),encoding='ascii')
        return {name:sha(data) for name,data in self.changed.items()}

def lua_literal(v):
    if v is None:return 'nil'
    if isinstance(v,bool):return 'true' if v else 'false'
    if isinstance(v,(int,float)):return repr(v)
    if isinstance(v,str):
        return '"'+''.join('\\%03d'%ord(c) if ord(c)<32 else '\\'+c if c in '\\"' else c for c in v)+'"'
    if isinstance(v,dict):return '{'+','.join('['+(str(k) if str(k).isdigit() else lua_literal(k))+']='+lua_literal(x) for k,x in v.items())+'}'
    if isinstance(v,list):return '{'+','.join(lua_literal(x) for x in v)+'}'
    raise TypeError(type(v))

def patch_table(raw,rows,helpers):
    """在原表完成后执行有限新增行，保留既有表构造指令及全部历史补丁。"""
    cur=helpers.Reader(raw);assert cur.raw(4)==b'\x1bLJ\x02';flags=cur.num();assert flags&2
    out=bytearray(raw[:cur.pos]);count=0
    while size:=cur.num():
        p=helpers.Reader(cur.raw(size));head=p.raw(4);ng,nn,nop=p.num(),p.num(),p.num();code=p.raw(nop*4);uv=p.raw(head[3]*2);pool=helpers.constants(p,ng);numbers=p.data[p.pos:]
        if code[-4]==76 and head[3]==0:
            target=code[-3];slot=head[2];arg=slot+(2 if flags&8 else 1);assert arg<250
            chunk=('local t=...; local marker="miao-local-import-v1"; '+''.join('t['+str(k)+']='+lua_literal(v)+';' for k,v in rows.items())+'return t').encode()
            pool=[helpers.pack(chunk),helpers.pack(b'loadstring')]+pool
            insert=helpers.ins('GGET',slot,ng)+helpers.ins('KSTR',arg,ng+1)+helpers.ins('CALL',slot,514)+helpers.ins('MOV',arg,target)+helpers.ins('CALL',slot,514)
            code=code[:-4]+insert+code[-4:];head=head[:2]+bytes([arg+1])+head[3:];count+=1
        block=head+helpers.encode(len(pool))+helpers.encode(nn)+helpers.encode(len(code)//4)+code+uv+b''.join(map(helpers.serial_constant,pool))+numbers
        out+=helpers.encode(len(block))+block
    assert count==1 and cur.pos==len(raw);out.append(0);return bytes(out)
