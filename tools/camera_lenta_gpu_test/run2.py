import numpy as np
from harness import *
from scene import frame
W,H=768,432
ip=Interp(W,H)
# corte de cena
A=frame(W,H,0,(0,0),None,seed=1).astype('f4'); B=frame(W,H,0,(0,0),None,seed=2).astype('f4')
ip.prep(A,B)
for t in (0.1,0.3,0.5,0.7,0.9):
    out=ip.frame(t); nr=A if t<0.5 else B
    pa,pb=psnr(out,A),psnr(out,B)
    print(f"corte t={t}: PSNR vs A {pa:5.1f}  vs B {pb:5.1f}  vs mistura {psnr(out,(A+B)/2):5.1f}  fração de pixels = repetição exata {np.mean(np.all(np.abs(out-nr)<2e-3,axis=2)):.3f}")
# fantasma: pan + fg oposto; mede erro nas bordas do objeto (anel) vs blend
bgv,fgv=(10,0),(-30,8)
A=frame(W,H,0,bgv,fgv).astype('f4'); B=frame(W,H,1,bgv,fgv).astype('f4')
ip.prep(A,B)
X,Y=np.meshgrid(np.arange(W),np.arange(H))
for t in (0.25,0.5):
    gt=frame(W,H,t,bgv,fgv).astype('f4'); out=ip.frame(t); bl=(1-t)*A+t*B
    cx=W*0.5+fgv[0]*t; cy=H*0.5+fgv[1]*t
    rr=np.sqrt((X-cx)**2+(Y-cy)**2); ring=(np.abs(rr-70)<14)
    e=lambda x: np.sqrt(np.mean((x[ring]-gt[ring])**2))
    print(f"anel da borda do objeto t={t}: RMSE ours {e(out):.4f}  blend {e(bl):.4f}  (menor=melhor); PSNR total ours {psnr(out,gt):.1f} blend {psnr(bl,gt):.1f}")
# movimento rápido demais (fora do alcance): deve cair p/ repetição, não lixo
for v in (80,120):
    A=frame(W,H,0,(v,0)).astype('f4'); B=frame(W,H,1,(v,0)).astype('f4'); ip.prep(A,B)
    for t in (0.5,):
        gt=frame(W,H,t,(v,0)).astype('f4'); out=ip.frame(t); bl=(1-t)*A+t*B
        print(f"muito rápido {v}px: ours {psnr(out,gt):.1f} blend {psnr(bl,gt):.1f} repetição {psnr(A if t<.5 else B,gt):.1f}")
# ruído de sensor
r=np.random.RandomState(0)
A=frame(W,H,0,(8,2)).astype('f4'); B=frame(W,H,1,(8,2)).astype('f4')
An=np.clip(A+r.normal(0,0.03,A.shape),0,1).astype('f4'); Bn=np.clip(B+r.normal(0,0.03,B.shape),0,1).astype('f4')
ip.prep(An,Bn); gt=frame(W,H,0.5,(8,2)).astype('f4'); out=ip.frame(0.5)
print(f"ruído σ=0.03: ours {psnr(out,gt):.1f} blend {psnr(0.5*An+0.5*Bn,gt):.1f}")
import PIL.Image as I
ip.prep(frame(W,H,0,bgv,fgv).astype('f4'),frame(W,H,1,bgv,fgv).astype('f4'))
o=ip.frame(0.5); g=frame(W,H,0.5,bgv,fgv); bl=0.5*frame(W,H,0,bgv,fgv)+0.5*frame(W,H,1,bgv,fgv)
I.fromarray((np.hstack([bl,o,g])[100:340]*255).astype('u1')).save('/workspace/lenta-test/cmp.png')
