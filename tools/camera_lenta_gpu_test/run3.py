import numpy as np
from harness import *
from scene import frame
W,H=768,432
ip=Interp(W,H)
def stat(name,A,B):
    ip.prep(A.astype('f4'),B.astype('f4'))
    f=np.frombuffer(ip.fab.read(),dtype='f2').reshape(ip.sizes[0][1],ip.sizes[0][0],4).astype('f4')
    c=f[...,2]
    print(f"{name:28s} custo médio {c.mean():.3f}  fração>0.07 {np.mean(c>0.07):.3f}  fração>0.10 {np.mean(c>0.10):.3f}  p90 {np.percentile(c,90):.3f}")
stat("pan 12",frame(W,H,0,(12,3)),frame(W,H,1,(12,3)))
stat("pan 40",frame(W,H,0,(40,8)),frame(W,H,1,(40,8)))
stat("pan+fg",frame(W,H,0,(10,0),(-30,8)),frame(W,H,1,(10,0),(-30,8)))
stat("estático",frame(W,H,0,(0,0)),frame(W,H,0,(0,0)))
stat("muito rápido 80",frame(W,H,0,(80,0)),frame(W,H,1,(80,0)))
stat("CORTE",frame(W,H,0,(0,0),None,seed=1),frame(W,H,0,(0,0),None,seed=2))
r=np.random.RandomState(0)
A=frame(W,H,0,(8,2)); B=frame(W,H,1,(8,2))
stat("ruído .03",np.clip(A+r.normal(0,.03,A.shape),0,1),np.clip(B+r.normal(0,.03,B.shape),0,1))
stat("ruído .06",np.clip(A+r.normal(0,.06,A.shape),0,1),np.clip(B+r.normal(0,.06,B.shape),0,1))
# fade/flash: B = A mais claro
stat("flash +0.25",A,np.clip(B+0.25,0,1))
print("---- razão custo/contraste")
def razao(name,A,B):
    ip.prep(A.astype('f4'),B.astype('f4'))
    f=np.frombuffer(ip.fab.read(),dtype='f2').reshape(ip.sizes[0][1],ip.sizes[0][0],4).astype('f4')
    c=f[...,2]
    L=np.frombuffer(ip.pyramid(ip.tA)[0].read(),dtype='u1').reshape(ip.sizes[0][1],ip.sizes[0][0]).astype('f4')/255
    con=(np.abs(L[:,3:]-L[:,:-3]).mean()+np.abs(L[3:,:]-L[:-3,:]).mean())/2
    # fração de blocos (16x9) com razão alta
    th,tw=c.shape[0]//9,c.shape[1]//16
    tiles=[(c[i*th:(i+1)*th,j*tw:(j+1)*tw].mean()) for i in range(9) for j in range(16)]
    print(f"{name:28s} custo {c.mean():.4f} contraste {con:.4f} razão {c.mean()/max(con,1e-3):.3f}  blocos com custo>0.5*contraste {np.mean(np.array(tiles)>0.5*con):.2f}")
razao("pan 12",frame(W,H,0,(12,3)),frame(W,H,1,(12,3)))
razao("pan 40",frame(W,H,0,(40,8)),frame(W,H,1,(40,8)))
razao("pan+fg",frame(W,H,0,(10,0),(-30,8)),frame(W,H,1,(10,0),(-30,8)))
razao("muito rápido 80",frame(W,H,0,(80,0)),frame(W,H,1,(80,0)))
razao("CORTE",frame(W,H,0,(0,0),None,seed=1),frame(W,H,0,(0,0),None,seed=2))
razao("ruído .06",np.clip(A+r.normal(0,.06,A.shape),0,1),np.clip(B+r.normal(0,.06,B.shape),0,1))
razao("flash +0.25",A,np.clip(B+0.25,0,1))
