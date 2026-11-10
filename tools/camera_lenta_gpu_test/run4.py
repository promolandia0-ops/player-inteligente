import numpy as np
from harness import *
from scene import frame
W,H=768,432
ip=Interp(W,H)
st=prog('stat.frag')
def razao(name,A,B):
    ip.prep(A.astype('f4'),B.astype('f4'))
    pa=ip.pyramid(ip.tA)
    out=tex(16,9,4,'f1')
    out.filter=(moderngl.NEAREST,moderngl.NEAREST)
    run(st,out,{},{'uFlow':ip.fab,'uLuma':pa[0]})
    d=np.frombuffer(out.read(),dtype='u1').reshape(9,16,4).astype('f4')
    c=((d[...,0]*255+d[...,1])/255)/4*1.0; k=((d[...,2]*255+d[...,3])/255)/4
    # R/G channels: v = (hi/255 *255 + lo)/255 -> hi + lo/255 ... decodificação
    cm=((d[...,0]+d[...,1]/255.0)/255.0*255.0)/255.0
    print(f"{name:20s}", end=' ')
    cc=(d[...,0]+d[...,1]/255.0)/255.0/ (1.0) ; kk=(d[...,2]+d[...,3]/255.0)/255.0
    # enc: s=clamp(v*4)*255; hi=floor(s); R=hi/255 -> byte=hi ; G=fract(s)
    cmean=np.mean(d[...,0]+d[...,1]/255.0)/255.0/4.0; kmean=np.mean(d[...,2]+d[...,3]/255.0)/255.0/4.0
    print(f"custo {cmean:.4f} contraste {kmean:.4f} razão {cmean/max(kmean,1e-3):.3f}")
razao("pan 12",frame(W,H,0,(12,3)),frame(W,H,1,(12,3)))
razao("pan+fg",frame(W,H,0,(10,0),(-30,8)),frame(W,H,1,(10,0),(-30,8)))
razao("muito rápido 80",frame(W,H,0,(80,0)),frame(W,H,1,(80,0)))
razao("CORTE",frame(W,H,0,(0,0),None,seed=1),frame(W,H,0,(0,0),None,seed=2))
