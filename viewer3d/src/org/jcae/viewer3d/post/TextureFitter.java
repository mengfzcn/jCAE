package org.jcae.viewer3d.post;

import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import javax.media.j3d.*;
import javax.vecmath.*;
import org.jcae.opencascade.Utilities;
import org.jcae.opencascade.jni.BRep_Builder;
import org.jcae.opencascade.jni.TopoDS_Compound;
import org.jcae.opencascade.jni.TopoDS_Shape;
import org.jcae.viewer3d.View;
import org.jcae.viewer3d.bg.ViewableBG;
import org.jcae.viewer3d.cad.CADDomain;
import org.jcae.viewer3d.cad.CADSelection;
import org.jcae.viewer3d.cad.FaceMesh;
import org.jcae.viewer3d.cad.ViewableCAD;
import org.jcae.viewer3d.cad.occ.OCCProvider;
import com.sun.j3d.utils.image.TextureLoader;

/**
 * A special View which allows to fit a texture on a given geometry.
 * It helps to compute the transformation which will match the texture
 * on the geometry
 * @author Jerome Robert
 */
public class TextureFitter extends View
{
	private static final long serialVersionUID = 2147414791584387916L;

	private static Transform3D computeTransform(
		Point3d[] triangle2d, Point3d[] triangle3d, int width, int height)
	{
		//bitmap 2D -> 1x1 square
		Matrix4d m2d=new Matrix4d();
		m2d.setM00(1.0/width);
		m2d.setM11(-1.0/height);
		m2d.setM22(1);
		m2d.setM33(1);
				
		Point3d[] p2d=new Point3d[]{
			new Point3d(triangle2d[0]),
			new Point3d(triangle2d[1]),
			new Point3d(triangle2d[2])};
		
		Transform3D t2d=new Transform3D(m2d);
		for(int i=0; i<3; i++)
			t2d.transform(p2d[i]);
		
		//3D -> 2D matrix
		Transform3D trsf1=normalizeTriangle(p2d);		
		trsf1.mulInverse(normalizeTriangle(triangle3d));
		return trsf1;		
	}
	
	private static GeometryArray createGeometry(OCCProvider occProvider)
	{	
		int[] ids=occProvider.getDomainIDs();

		int nbint=0;
		int nbfl=0;
		ArrayList meshes=new ArrayList();
		for(int i=0; i<ids.length; i++)
		{
			CADDomain d = (CADDomain) occProvider.getDomain(i);
			Iterator it = d.getFaceIterator();
			if(it!=null)
			while(it.hasNext())
			{
				FaceMesh fm=(FaceMesh) it.next();
				nbint += fm.getMesh().length;
				nbfl+=fm.getNodes().length;
				meshes.add(fm);
			}
		}
		
		int[] trias=new int[nbint];
		float[] nodes=new float[nbfl];
		int destPosInt=0, destPosFl=0;
		for(int i=0; i<meshes.size(); i++)
		{
			FaceMesh fm=(FaceMesh) meshes.get(i);
			int[] m=fm.getMesh();
			float[] n=fm.getNodes();
			System.arraycopy(n, 0, nodes, destPosFl, n.length);
				
			System.arraycopy(m, 0, trias, destPosInt, m.length);
			for(int j=0; j<m.length; j++)
			{
				trias[destPosInt+j]+=destPosFl/3;
			}
			destPosInt+=m.length;
			destPosFl+=n.length;
		}
		
		IndexedTriangleArray ita=new IndexedTriangleArray(
			nodes.length/3,GeometryArray.COORDINATES, trias.length);
		ita.setCoordinates(0, nodes);
		ita.setCoordinateIndices(0, trias);
		return ita;
	}
	/**
	 * Return selected faces in a viewable 
	 * @param viewable The viewable on witch picking has been done
	 * @param shape The shape from which faces are selected. This is the one
	 * used to create the viewable.
	 * @return A compound including all selected faces
	 */
	public static TopoDS_Compound getSelectFaces(ViewableCAD viewable, TopoDS_Shape shape)
	{
		CADSelection[] ss=viewable.getSelection();
		ArrayList faces=new ArrayList();
		for(int i=0; i<ss.length; i++)
		{
			int[] ids=ss[i].getFaceIDs();
			for(int j=0; j<ids.length; j++)
				faces.add(Utilities.getFace(shape, ids[j]));							
		}
		
		if(faces.size()>0)
		{
			BRep_Builder bb=new BRep_Builder();
			TopoDS_Compound compound=new TopoDS_Compound();
			bb.makeCompound(compound);
			
			for(int i=0; i<faces.size(); i++)
				bb.add(compound, (TopoDS_Shape) faces.get(i));
			
			return compound;
		}
		else
			return null;
	}

	/**
	 * Compute the transformation of triangle src to triangle dst
	 * @param dst The 3 transformed points
	 * @param src The 3 points to transform
	 */
	public static Matrix4d getTransform(Point3d[] dst, Point3d[] src)
	{		
	    Matrix4d m=new Matrix4d();
        Transform3D trsf1=computeTransform(dst, src, 1, 1);
        trsf1.get(m);
        return m;
	}

	/**
	 * Compute the transformation which will transform the triangle
	 * (O, x, y) to (p1, p2, p3)
	 */
	private static Transform3D normalizeTriangle(Point3d[] p)
	{
		Vector3d imgX=new Vector3d();
		Vector3d imgY=new Vector3d();
		Vector3d imgZ=new Vector3d();
		
		imgX.sub(p[1], p[0]);
		imgY.sub(p[2], p[0]);
		imgZ.cross(imgX, imgY);
		imgZ.scale((imgX.length()+imgY.length())/imgZ.length()/2);		
		Matrix3d rotation=new Matrix3d();
		rotation.setColumn(0, imgX);
		rotation.setColumn(1, imgY);
		rotation.setColumn(2, imgZ);
		Transform3D toReturn=new Transform3D();
		toReturn.set(rotation, new Vector3d(p[0]), 1);
		return toReturn;		
	}

	private BufferedImage image;

	private TexCoordGeneration texCoordGeneration;
	
	private ViewableBG textureViewable;
	
	/**
	 * @param frame the window owning the widget
	 */
	public TextureFitter(Window frame)
	{
		super(frame, false, true);
	}
	
	private Appearance createAppearance(Point3d[] triangle2d, Point3d[] triangle3d)
	{		
		Appearance toReturn=new Appearance();
		Texture theTexture = createTexture(image);				
		texCoordGeneration = new TexCoordGeneration(
        	TexCoordGeneration.EYE_LINEAR,
        	TexCoordGeneration.TEXTURE_COORDINATE_2);
		texCoordGeneration.setCapability(TexCoordGeneration.ALLOW_PLANE_WRITE);
		updateTexture(triangle2d, triangle3d);
        toReturn.setTexture(theTexture);
        toReturn.setTexCoordGeneration(texCoordGeneration);                
		return toReturn;
	}
	
	private Texture createTexture(BufferedImage image)
	{
		TextureLoader tl=new TextureLoader(image, TextureLoader.ALLOW_NON_POWER_OF_TWO);
		Map map=queryProperties();
		int textureWidthMax=((Integer)map.get("textureWidthMax")).intValue();
		int textureHeightMax=((Integer)map.get("textureHeightMax")).intValue();
		boolean textureNonPowerOfTwoAvailable=
			((Boolean)map.get("textureNonPowerOfTwoAvailable")).booleanValue();

		while(true)
		{
			ImageComponent2D img=tl.getImage();
			if(img.getWidth()<=textureWidthMax && img.getHeight()<=textureHeightMax)
				break;
			tl=new TextureLoader(tl.getScaledImage(0.5f, 0.5f).getImage(),
				TextureLoader.ALLOW_NON_POWER_OF_TWO);
		}
		
		int flags=TextureLoader.GENERATE_MIPMAP;
		if(!textureNonPowerOfTwoAvailable)
			flags=flags|TextureLoader.ALLOW_NON_POWER_OF_TWO;
		
		tl=new TextureLoader(tl.getImage().getImage(), flags);
				
		return tl.getTexture();
	}
	
	/** 
	 * @param shape The shape on which the texture must be displayed
	 * @param triangle2d The 2D points (z=0) picked on the bitmap
	 * @param triangle3d The 3D points picked on the geometry
	 * @param image The image to display
	 */
	public void displayTexture(TopoDS_Shape shape,
		Point3d[] triangle2d, Point3d[] triangle3d, BufferedImage image)
	{
		this.image=image;
		OCCProvider occProvider=new OCCProvider(shape);
		Shape3D shape3D=new Shape3D(createGeometry(occProvider));
		shape3D.setAppearance(createAppearance(triangle2d, triangle3d));
		BranchGroup bg=new BranchGroup();
		bg.addChild(shape3D);
		textureViewable=new ViewableBG(bg);
		remove(textureViewable);
		add(textureViewable);
	}
	
	/** 
	 * Move the texture
	 * @param triangle2d The 2D points (z=0) picked on the bitmap
	 * @param triangle3d The 3D points picked on the geometry
	 */
	public void updateTexture(Point3d[] triangle2d, Point3d[] triangle3d)
	{
        
		Matrix4f m=new Matrix4f();
        Transform3D trsf1=computeTransform(triangle2d, triangle3d,
        	image.getWidth(), image.getHeight());
        trsf1.get(m);
        Vector4f vS=new Vector4f();
        Vector4f vT=new Vector4f();
        m.getRow(0, vS);
        m.getRow(1, vT);
        texCoordGeneration.setPlaneS(vS);
        texCoordGeneration.setPlaneT(vT);
	}	
}
