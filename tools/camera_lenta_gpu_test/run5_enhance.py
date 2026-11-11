"""Validação do 'Melhorar imagem' (enhance.frag) fora do aparelho: mais nítido, sem halos/artefatos, sem amplificar ruído/blocos,
sem tremida no tempo, e custo por quadro. Cena sintética SFW (fundo texturizado + discos + faixa de degradê) degradada como vídeo
comprimido (desfoque σ≈0,8 + blocos 8×8 + ruído + faixas)."""
import numpy as np, time, sys
from scipy import ndimage
from harness import *
from scene2 import frame2
W,H=1280,720
enh=prog('enhance.frag')
def make_clean():
    img=frame2(W,H,0).astype('f4')
    # faixa de degradê suave (céu) p/ medir faixas/desbloqueio, e linhas finas p/ nitidez
    gy=np.linspace(0.30,0.55,W,dtype='f4')[None,:,None]*np.ones((1,1,3),'f4')
    img[560:700,:,:]=gy*np.array([1.0,1.05,1.2],'f4')
    for x in range(40,W,80): img[40:200,x:x+2,:]=1.0
    return np.clip(img,0,1)
def degrade_video(c,seed):
    r=np.random.RandomState(seed)
    b=ndimage.gaussian_filter(c,(0.8,0.8,0))
    dc=r.normal(0,0.008,(H//8+1,W//8+1,1)).repeat(8,0).repeat(8,1)[:H,:W]
    b=b+dc+r.normal(0,0.006,b.shape)
    return np.clip(np.round(b*255)/255,0,1).astype('f4')
def enhance(img,forca=0.6):
    t=tex(W,H,4,'f4',np.dstack([img,np.ones(img.shape[:2],'f4')])[::-1].astype('f4').tobytes())
    out=tex(W,H,4,'f4'); run(enh,out,{'uForca':forca},{'uSrc':t})
    return np.clip(np.frombuffer(out.read(),dtype='f4').reshape(H,W,4)[::-1,:,:3],0,1)
def boost(c):   # mesma saturação/contraste do shader aplicados ao GT (comparação justa)
    l=(c*np.array([0.299,0.587,0.114])).sum(2,keepdims=True); c=l+(c-l)*1.06; return np.clip((c-0.5)*1.04+0.5,0,1)
def luma(c): return (c*np.array([0.299,0.587,0.114])).sum(2)
clean=make_clean(); gt=boost(clean)
d1=degrade_video(clean,1); d2=degrade_video(clean,2)
o1=enhance(d1); o2=enhance(d2)
gl=luma(clean); gx=ndimage.sobel(gl,1); gy_=ndimage.sobel(gl,0); gm=np.hypot(gx,gy_)/8
edge=gm>0.08
def gradmean(c,m): l=luma(c); return (np.hypot(ndimage.sobel(l,1),ndimage.sobel(l,0))/8)[m].mean()
lo=ndimage.minimum_filter(gl,5); hi=ndimage.maximum_filter(gl,5)
def over(c): l=luma(boost(clean)*0+c); return np.mean((l>hi*1.0+0.04)|(l<lo*1.0-0.04))   # halo/ringing: passa do envelope local do GT
flat=np.zeros((H,W),bool); flat[580:690,50:W-50]=True
def blockiness(c):
    l=luma(c)[580:690]; dx=np.abs(np.diff(l,axis=1)); bnd=np.zeros(dx.shape[1],bool); bnd[7::8]=True
    return dx[:,bnd].mean()*255
def max_step(c): l=luma(c)[600:640].mean(0); d=np.abs(np.diff(l)); return d.max()*255
print("                           entrada   melhorada   GT(sem degradar)")
print(f"PSNR vs GT (c/ cor igual) {psnr(d1,gt):8.2f} {psnr(o1,gt):10.2f}")
print(f"nitidez (|∇| em bordas)   {gradmean(d1,edge):8.4f} {gradmean(o1,edge):10.4f}   {gradmean(gt,edge):8.4f}   → razão {gradmean(o1,edge)/gradmean(d1,edge):.2f}×")
print(f"halo/ringing (% pixels)   {100*over(d1):8.3f} {100*over(o1):10.3f}")
hp=lambda c: (luma(c)-ndimage.gaussian_filter(luma(c),6))
tx=np.zeros((H,W),bool); tx[250:520,100:W-100]=True; tx&=~ndimage.binary_dilation(edge,iterations=6)
print(f"ruído em área plana (σ HP) {hp(d1)[flat].std()*255:8.2f} {hp(o1)[flat].std()*255:10.2f}")
print(f"detalhe em textura (σ HP)  {hp(d1)[tx].std()*255:8.2f} {hp(o1)[tx].std()*255:10.2f}   {hp(gt)[tx].std()*255:8.2f}  (GT)")
print(f"degrau nas bordas de bloco /255 {blockiness(d1):8.3f} {blockiness(o1):10.3f}")
print(f"maior degrau da faixa(/255){max_step(d1):7.2f} {max_step(o1):10.2f}")
tin=np.abs(d1-d2).mean(); tout=np.abs(o1-o2).mean()
print(f"tremida temporal |Δ| quadro1↔2: entrada {tin*255:.2f}  saída {tout*255:.2f}  (razão {tout/tin:.2f}; <1,2 = não amplifica)")
print(f"luma média: entrada {luma(d1).mean():.4f} saída {luma(o1).mean():.4f} | pixels saturados novos: {np.mean((o1>=0.999).any(2))*100:.3f}% (entrada {np.mean((d1>=0.999).any(2))*100:.3f}%)")
import PIL.Image as I
I.fromarray((np.hstack([d1[40:240,0:400],o1[40:240,0:400],clean[40:240,0:400]])*255).astype('u1')).save('/tmp/enh_cmp.png')
# custo por quadro (llvmpipe = CPU; só relativo!)
for (w,h) in ((1280,720),(1920,1080)):
    img=np.dstack([np.random.rand(h,w,3).astype('f4'),np.ones((h,w),'f4')]).astype('f4')
    t=tex(w,h,4,'f4',img.tobytes()); out=tex(w,h,4,'f4'); cp=prog('down.frag')
    def tm(p,u):
        run(p,out,u,{'uSrc':t}); t0=time.time()
        for _ in range(8): run(p,out,u,{'uSrc':t})
        return (time.time()-t0)/8*1000
    te=tm(enh,{'uForca':0.6}); tc=tm(cp,{})
    print(f"custo {w}x{h}: enhance {te:.1f} ms vs cópia {tc:.1f} ms (CPU llvmpipe) → {te/tc:.1f}× uma cópia; 13 leituras de textura/pixel, ~60 ALU")
