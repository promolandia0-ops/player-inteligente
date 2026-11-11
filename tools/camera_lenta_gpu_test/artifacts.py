"""Métrica de 'pipocos' (artefatos de interpolação) antes/depois: LENTA_SHADERS=<dir> python artifacts.py
Sequência de N quadros de origem degradados (ruído + blocos); interpola 7 pontos entre cada par (t=k/8) e compara com a verdade-terreno
(cena limpa na posição exata). Métricas:
  specks/quadro : nº de manchas pequenas (<300 px) com erro absoluto médio > 0,10 (os 'pipocos')
  err>0.1 (%)   : % de pixels com erro > 0,10
  flicker       : média |Δerro temporal| = |(o_k−o_{k−1}) − (g_k−g_{k−1})| entre quadros de saída consecutivos (instabilidade no tempo)
  PSNR          : vs verdade-terreno
"""
import numpy as np, sys, os
from scipy import ndimage
from harness import *
from scene2 import frame2, degrade
W,H=768,432
DISCS0=((-18,5,70),(14,-9,45),(-6,-14,30))
Xg,Yg=np.meshgrid(np.arange(W,dtype=np.float32),np.arange(H,dtype=np.float32))
def banda(motion,k0,t,larg=9):
    """máscara da faixa em volta das bordas dos discos (A, ponto interpolado e B): erro ali é oclusão legítima, não 'pipoco'."""
    out=np.zeros((H,W),bool)
    for i,(vx,vy,r) in enumerate(motion.get('discs',DISCS0)):
        for kk in (k0,k0+t,k0+1):
            cx=W*(0.3+0.2*i)+vx*kk; cy=H*(0.45+0.08*i)+vy*kk
            out|=np.abs(np.sqrt((Xg-cx)**2+(Yg-cy)**2)-r)<larg
    return out
def run_seq(ip, motion, N=7, label="", noise=0.015, blocks=0.012):
    ip.reset()
    clean=[frame2(W,H,k,**motion).astype('f4') for k in range(N)]
    src=[degrade(c,k,noise,blocks) for k,c in enumerate(clean)]
    outs=[];gts=[];bands=[]
    for k in range(N-1):
        ip.prep(src[k],src[k+1])
        for j in range(1,8):
            t=j/8; outs.append(ip.frame(t)); gts.append(frame2(W,H,k+t,**motion).astype('f4')); bands.append(banda(motion,k,t))
    if REF: outs=REF(src,N)
    specks=[];big=[];fl=[];ps=[]
    for i,(o,g) in enumerate(zip(outs,gts)):
        e=np.abs(o-g).mean(axis=2); m=(e>0.10)&~bands[i]
        lab,n=ndimage.label(m)
        if n:
            sizes=ndimage.sum(m,lab,range(1,n+1)); specks.append(int(np.sum(sizes<300)))
        else: specks.append(0)
        big.append(100*m.mean()); ps.append(psnr(o,g))
        if i>0: fl.append(np.abs((o-outs[i-1])-(g-gts[i-1])).mean())
    return dict(specks=np.mean(specks),err=np.mean(big),flicker=np.mean(fl)*100,psnr=np.mean(ps)), outs, gts
REF=None
def ref_blend(src,N):
    return [(1-j/8)*src[k]+(j/8)*src[k+1] for k in range(N-1) for j in range(1,8)]
if __name__=="__main__":
    if os.environ.get('LENTA_REF')=='blend': REF=ref_blend
    ip=Interp(W,H)
    print("shaders:",SH, "NEW" if NEW else "BASE")
    cases={"pan+3 discos":(dict(),0.015,0.012),"pan rápido+discos":(dict(bgv=(20,-6),discs=((-30,8,70),(26,-14,45),(-12,-24,30))),0.015,0.012),
           "lento":(dict(bgv=(3,1),discs=((-5,2,70),(4,-3,45),(-2,-4,30))),0.015,0.012),
           "grade repetitiva":(dict(grade=True,bgv=(3,1)),0.02,0.015),"grade repetitiva + pan médio":(dict(grade=True,bgv=(4.4,-1.5)),0.02,0.015),"ruído forte σ=.04 + blocos .03":(dict(),0.04,0.03),"ruído forte + pan rápido":(dict(bgv=(20,-6),discs=((-30,8,70),(26,-14,45),(-12,-24,30))),0.04,0.03)}
    cases={k:v for k,v in cases.items() if not k.startswith('grade')} if len(sys.argv)<=1 else cases
    if len(sys.argv)>1: cases={k:v for k,v in cases.items() if k in sys.argv[1:]}
    tot=[]
    for n,(m,nz,bl) in cases.items():
        r,outs,gts=run_seq(ip,m,N=5 if nz>0.03 else 7,noise=nz,blocks=bl)
        tot.append(r)
        print(f"{n:20s} specks/quadro {r['specks']:6.1f} | pixels err>0.1 {r['err']:5.2f}% | flicker {r['flicker']:.3f} | PSNR {r['psnr']:.1f}")
        if n=="pan+3 discos":
            pass
            import PIL.Image as I
            i=len(outs)//2
            I.fromarray((np.hstack([outs[i],gts[i]])*255).astype('u1')).save('/tmp/art_'+('new' if NEW else 'base')+'.png')
    print("MÉDIA specks %.1f | err %.2f%% | flicker %.3f | PSNR %.1f"%tuple(np.mean([r[k] for r in tot]) for k in ('specks','err','flicker','psnr')))
