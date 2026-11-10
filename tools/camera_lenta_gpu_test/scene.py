import numpy as np
def make_bg(seed, nw=160):
    r=np.random.RandomState(seed)
    fr=np.exp(r.uniform(np.log(0.015),np.log(0.6),nw))
    comps=[(fr[i], r.uniform(0,6.28), r.uniform(0,6.28), 0.12/np.sqrt(fr[i]/0.015)*r.uniform(0.5,1.0), r.uniform(0,6.28)) for i in range(nw)]
    blobs=[(r.uniform(-200,1100), r.uniform(-200,700), r.uniform(8,40), r.uniform(-1,1,3)) for _ in range(120)]
    return comps, blobs
def bg(comps,blobs,X,Y):
    v=np.zeros(X.shape+(3,),np.float32)
    for k,(f,a,b,amp,ph) in enumerate(comps):
        v[...,k%3]+=amp*3*np.sin(f*(np.cos(a)*X+np.sin(a)*Y)+ph)
    for (cx,cy,s,col) in blobs:
        g=np.exp(-((X-cx)**2+(Y-cy)**2)/(2*s*s)).astype(np.float32)
        v+=g[...,None]*col[None,None,:]
    v=v/ (np.abs(v).max()+1e-6)
    return np.clip(0.5+0.5*v,0,1).astype(np.float32)
def frame(W,H,t,bgv=(12,3),fgv=None,seed=1,fgseed=9,r=70):
    X,Y=np.meshgrid(np.arange(W,dtype=np.float32),np.arange(H,dtype=np.float32))
    comps,blobs=make_bg(seed)
    img=bg(comps,blobs,X-bgv[0]*t,Y-bgv[1]*t)
    if fgv is not None:
        c2,b2=make_bg(fgseed,8)
        cx=W*0.5+fgv[0]*t; cy=H*0.5+fgv[1]*t
        m=((X-cx)**2+(Y-cy)**2)<r*r
        fgimg=bg(c2,b2,X-fgv[0]*t,Y-fgv[1]*t)
        img=np.where(m[...,None],fgimg,img)
    return np.round(img*255)/255.0
