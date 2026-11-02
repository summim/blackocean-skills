import sys, numpy as np, trimesh, matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d.art3d import Poly3DCollection
core = trimesh.load(sys.argv[1]+'/obj/fist_core.obj', process=False)
shell = trimesh.load(sys.argv[1]+'/obj/fist_shell.obj', process=False)
views=[('front (knuckles)',0,90),('side',0,0),('3/4',25,50),('top',89,90)]
fig=plt.figure(figsize=(16,8))
L=np.array([0.4,0.7,0.6]); L/=np.linalg.norm(L)
for i,(t,el,az) in enumerate(views):
  for j,(m,col) in enumerate([(core,(1,.6,.2)),(shell,(1,.3,.1))]):
    ax=fig.add_subplot(2,4,i+1+4*j,projection='3d')
    tri=m.vertices[m.faces]; n=m.face_normals; s=np.clip(n@L,0,1)*0.75+0.25
    pc=Poly3DCollection(tri,facecolors=[(col[0]*k,col[1]*k,col[2]*k) for k in s],edgecolor='none')
    ax.add_collection3d(pc)
    # map: plot X->x, Z->y(depth), Y->z(up)
    pc.set_verts([np.c_[t[:,0],t[:,2],t[:,1]] for t in tri])
    ax.set_xlim(-1.2,1.2); ax.set_ylim(-2.3,1); ax.set_zlim(-1.2,1.2)
    ax.set_box_aspect((2.4,3.3,2.4)); ax.view_init(el, az+(-90 if az==90 else 0)); ax.axis('off'); ax.set_title(('core ' if j==0 else 'shell ')+t)
plt.tight_layout(); plt.savefig(sys.argv[2],dpi=70)
