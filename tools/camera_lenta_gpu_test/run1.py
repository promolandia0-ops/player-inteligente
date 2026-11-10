import numpy as np, time, sys
from harness import *
from scene import frame
W,H=768,432
ip=Interp(W,H)
def case(name,bgv,fgv=None):
    A=frame(W,H,0,bgv,fgv).astype('f4'); B=frame(W,H,1,bgv,fgv).astype('f4')
    t0=time.time(); ip.prep(A,B); dt=time.time()-t0
    res=[]
    for t in (0.25,0.5,0.75):
        gt=frame(W,H,t,bgv,fgv).astype('f4')
        out=ip.frame(t)
        bl=(1-t)*A+t*B; nr=A if t<0.5 else B
        res.append((t,psnr(out,gt),psnr(bl,gt),psnr(nr,gt)))
    print(f"{name:34s} " + " | ".join(f"t={t}: ours {a:5.1f} blend {b:5.1f} rep {c:5.1f}" for t,a,b,c in res), f"[{dt:.1f}s flow]")
    return ip
case("pan 12px",(12,3))
case("pan 25px diagonal",(25,-10))
case("pan 40px (5%)",(40,8))
case("pan + fg oposto (oclusão)",(10,0),(-22,6))
case("pan lento 2.5px",(2.5,1.2))
case("estático",(0,0))
