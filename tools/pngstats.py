#!/usr/bin/env python3
"""Rough statistics of a PNG screenshot: dimensions and dominant colors (no dependency)."""
import sys, zlib, struct
from collections import Counter
def read_png(path):
    d=open(path,'rb').read(); pos=8; idat=b''
    while pos<len(d):
        ln=struct.unpack('>I',d[pos:pos+4])[0]; typ=d[pos+4:pos+8]; data=d[pos+8:pos+8+ln]
        if typ==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',data[:10])
        elif typ==b'IDAT': idat+=data
        pos+=12+ln
    raw=zlib.decompress(idat); ch=4 if ct==6 else 3; stride=w*ch
    rows=[]; prev=bytearray(stride); i=0
    for y in range(h):
        f=raw[i]; i+=1; line=bytearray(raw[i:i+stride]); i+=stride
        for x in range(stride):
            a=line[x-ch] if x>=ch else 0; b=prev[x]; c=prev[x-ch] if x>=ch else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+(a+b)//2)&255
            elif f==4:
                p=a+b-c; pa=abs(p-a); pb=abs(p-b); pc=abs(p-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        rows.append(bytes(line)); prev=line
    return w,h,ch,rows
w,h,ch,rows=read_png(sys.argv[1]); print('dimensions',w,h)
c=Counter()
for y in range(0,h,6):
    r=rows[y]
    for x in range(0,w,6):
        o=x*ch; c[(r[o]//24*24,r[o+1]//24*24,r[o+2]//24*24)]+=1
for col,n in c.most_common(12): print('#%02x%02x%02x'%col,n)
