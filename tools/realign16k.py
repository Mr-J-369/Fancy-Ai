#!/usr/bin/env python3
"""Repack an ELF64 shared lib so every PT_LOAD segment is 16 KB-aligned.

Strategy: keep all virtual addresses (so relocations/symbols stay valid), and only
shift FILE offsets by inserting zero padding between segments so that, for each
PT_LOAD, (p_offset % 16384) == (p_vaddr % 16384), then set p_align = 0x4000.
Every offset field (program headers, section headers, e_shoff) is remapped.
Vaddrs, sizes, content bytes, dynamic table, symbols and relocations are untouched.
"""
import struct, sys

PAGE = 0x4000
PT_LOAD = 1

def u16(b,o): return struct.unpack_from('<H', b, o)[0]
def u32(b,o): return struct.unpack_from('<I', b, o)[0]
def u64(b,o): return struct.unpack_from('<Q', b, o)[0]

def realign(src, dst):
    with open(src,'rb') as f: data = f.read()
    assert data[:4]==b'\x7fELF' and data[4]==2 and data[5]==1, "need ELF64 LE"
    e_phoff = u64(data,0x20); e_shoff = u64(data,0x28)
    e_phentsize=u16(data,0x36); e_phnum=u16(data,0x38)
    e_shentsize=u16(data,0x3a); e_shnum=u16(data,0x3c)

    phdrs=[]
    for i in range(e_phnum):
        b=e_phoff+i*e_phentsize
        phdrs.append(dict(i=i, type=u32(data,b), off=u64(data,b+0x08), vaddr=u64(data,b+0x10)))

    loads=sorted([p for p in phdrs if p['type']==PT_LOAD], key=lambda p:p['off'])
    insertions=[]; S=0
    for p in loads:
        new_off=p['off']+S
        pad=(p['vaddr']-new_off)%PAGE
        if pad: insertions.append((p['off'],pad))
        S+=pad

    def shift(x): return x+sum(pad for (O,pad) in insertions if O<=x)

    # build padded file
    out=bytearray(); prev=0
    for (O,pad) in sorted(insertions):
        out+=data[prev:O]; out+=b'\x00'*pad; prev=O
    out+=data[prev:]

    new_phoff=shift(e_phoff); new_shoff=shift(e_shoff)
    struct.pack_into('<Q', out, 0x20, new_phoff)
    struct.pack_into('<Q', out, 0x28, new_shoff)
    # program headers: remap p_offset (all), p_align=PAGE (LOAD only)
    for p in phdrs:
        base=new_phoff+p['i']*e_phentsize
        struct.pack_into('<Q', out, base+0x08, shift(p['off']))
        if p['type']==PT_LOAD:
            struct.pack_into('<Q', out, base+0x30, PAGE)
    # section headers: remap sh_offset (read originals from `data`)
    for i in range(e_shnum):
        ob=e_shoff+i*e_shentsize
        sh_off=u64(data,ob+0x18)
        struct.pack_into('<Q', out, new_shoff+i*e_shentsize+0x18, shift(sh_off))

    with open(dst,'wb') as f: f.write(out)
    print(f"  {src.split('/')[-1]}: +{S} bytes padding, {len(insertions)} segments shifted")

if __name__=='__main__':
    realign(sys.argv[1], sys.argv[2])
