"""Cena sintética (SFW) mais difícil: fundo texturizado em panorâmica + 3 discos com movimentos diferentes (oclusões) + ruído de sensor
+ "blocos de compressão" (deslocamento DC aleatório por bloco 8×8, diferente a cada quadro) — o tipo de coisa que gera 'pipocos'."""
import numpy as np
from scene import make_bg, bg
import hashlib, os, pickle
_CACHE='/tmp/scene2_cache'; os.makedirs(_CACHE,exist_ok=True)
def frame2(W,H,k,**kw):
    key=hashlib.md5(repr((W,H,round(float(k),6),sorted(kw.items()))).encode()).hexdigest(); f=f'{_CACHE}/{key}.npy'
    if os.path.exists(f): return np.load(f)
    r=_frame2(W,H,k,**kw); np.save(f,r.astype('f4')); return r
def _frame2(W,H,k,seed=1,grade=False,noise=0.015,blocks=0.012,bgv=(9,2.5),discs=((-18,5,70),(14,-9,45),(-6,-14,30)),rs=None):
    X,Y=np.meshgrid(np.arange(W,dtype=np.float32),np.arange(H,dtype=np.float32))
    comps,blobs=make_bg(seed)
    img=bg(comps,blobs,X-bgv[0]*k,Y-bgv[1]*k)
    if grade:   # grade/cerca repetitiva (ambígua p/ casamento de blocos) sobre o fundo
        gx=X-bgv[0]*k; gy=Y-bgv[1]*k
        img=np.clip(0.55*img+0.45*(0.5+0.5*(np.sin(2*np.pi*gx/10)*np.sin(2*np.pi*gy/10))[...,None]),0,1)
    for i,(vx,vy,r) in enumerate(discs):
        c2,b2=make_bg(20+i,8)
        cx=W*(0.3+0.2*i)+vx*k; cy=H*(0.45+0.08*i)+vy*k
        m=((X-cx)**2+(Y-cy)**2)<r*r
        img=np.where(m[...,None],bg(c2,b2,X-vx*k,Y-vy*k),img)
    return img
def degrade(img,idx,noise=0.015,blocks=0.012):
    """ruído + DC por bloco 8×8, por quadro de ORIGEM (idx)."""
    r=np.random.RandomState(1000+idx); H,W=img.shape[:2]
    out=img+r.normal(0,noise,img.shape)
    dc=r.normal(0,blocks,(H//8+1,W//8+1,1)).repeat(8,0).repeat(8,1)[:H,:W]
    return np.clip(np.round((out+dc)*255)/255,0,1).astype('f4')
