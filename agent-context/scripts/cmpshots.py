"""Compares two folders of screenshots (made by the rig) pixel by pixel: same, DIFF with the changed box, or MISSING.
Usage: python3 cmpshots.py <before dir> <after dir> (needs Pillow)."""
import sys,os
from PIL import Image, ImageChops
a,b=sys.argv[1],sys.argv[2]
bad=0
for f in sorted(os.listdir(a)):
    if not f.endswith('.png'): continue
    pa,pb=os.path.join(a,f),os.path.join(b,f)
    if not os.path.exists(pb): print('MISSING',f); bad+=1; continue
    ia,ib=Image.open(pa).convert('RGB'),Image.open(pb).convert('RGB')
    if ia.size!=ib.size: print('SIZE',f,ia.size,ib.size); bad+=1; continue
    d=ImageChops.difference(ia,ib).getbbox()
    if d: print('DIFF',f,d); bad+=1
    else: print('same',f)
print('differences:',bad)
