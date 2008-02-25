/* jCAE stand for Java Computer Aided Engineering. Features are : Small CAD
   modeler, Finite element mesher, Plugin architecture.

    Copyright (C) 2004,2005,2006, by EADS CRC
    Copyright (C) 2007,2008 by EADS France

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA */

package org.jcae.mesh.amibe.ds;

import org.jcae.mesh.amibe.metrics.Matrix3D;
import org.jcae.mesh.amibe.traits.Traits;
import org.jcae.mesh.amibe.traits.MeshTraitsBuilder;
import org.jcae.mesh.amibe.traits.TriangleTraitsBuilder;
import org.jcae.mesh.amibe.util.KdTree;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mesh data structure.
 * A mesh is composed of triangles, edges and vertices.  There are
 * many data structures to represent meshes, and we focused on the
 * following constraints:
 * <ul>
 *   <li>Memory usage must be minimal in order to perform very large
 *       meshes.</li>
 *   <li>Mesh traversal must be cheap.</li>
 * </ul>
 * We decided to implement a triangle-based data structure which is known
 * to be more memory efficient.  A {@link Triangle} is composed of three
 * {@link Vertex}, three links to adjacent triangles, and each vertex
 * contains a backward link to one of its incident triangles.  It is
 * then possible to loop within triangles or around vertices.
 * 
 * But there is also a need for lighter data structures.  For instance,
 * visualization does not need adjacency relations, we only need to
 * store triangle vertices.
 *
 * {@link Mesh} constructor takes an optional {@link MeshTraitsBuilder}
 * argument to fully describe the desired mesh data structure.  Once a
 * {@link Mesh} instance is created, its features cannot be modified.
 * With this argument, it is possible to specify if adjacent relations
 * between triangles have to be computed, if an octree is needed to
 * locate vertices, if triangles and/or nodes are stored in a list
 * or a set, etc.  Example:
 * <pre>
   MeshTraitsBuilder mtb = new MeshTraitsBuilder();
   // Store triangles into a set
   mtb.addTriangleSet();
   TriangleTraitsBuilder ttb = new TriangleTraitsBuilder();
   // Store adjacency relations with HalfEdge
   ttb.addHalfEdge();
   mtb.add(ttb);
   // Create a new instance with these features
   Mesh mesh = new Mesh(mtb);
   // Then each triangle created by mesh.createTriangle
   // will contain objects needed to store adjacency relations.
   Triangle t = (Triangle) mesh.createTriangle(...);
   // Vertices must be created by mesh.createVertex
   Vertex v = (Vertex) mesh.createVertex(...);
 * </pre>
 * 
 */

public class Mesh implements Serializable
{
	private static Logger logger=Logger.getLogger(Mesh.class.getName());
	
	/**
	 * User-defined traits builder.
	 */
	protected final MeshTraitsBuilder traitsBuilder;

	/**
	 * User-defined traits
	 */
	protected final Traits traits;

	/**
	 * User-defined mesh parameters.
	 */
	protected final MeshParameters meshParameters;

	/**
	 * Vertex at infinite.
	 */
	public Vertex outerVertex = new OuterVertex();
	
	//  Triangle list
	private final Collection<Triangle> triangleList;
	
	//  Node list.
	private final Collection<Vertex> nodeList;

	//  Entity factory
	protected ElementFactoryInterface factory = null;

	//  Set to true by Mesh2D, this subclass connects outer triangles
	protected boolean outerTrianglesAreConnected = false;
	
	protected int maxLabel = 0;
	
	// Utility class to improve debugging output
	private static class OuterVertex extends Vertex
	{
		
		public OuterVertex()
		{
			super(null);
			setReadable(false);
			setWritable(false);
		}

		@Override
		public String toString()
		{
			return "outer";
		}
	}

	/**
	 * Stores Integer(0..2) into an array to reuse these immutable objects.
	 */
	private static final Integer [] int3 = new Integer[3];
	static {
		int3[0] = Integer.valueOf(0);
		int3[1] = Integer.valueOf(1);
		int3[2] = Integer.valueOf(2);
	}

	/**
	 * Creates an empty mesh.  When no <code>MeshTraitsBuilder</code>
	 * is passed, {@link MeshTraitsBuilder#getDefault3D} is called
	 * implicitly.
	 */
	public Mesh()
	{
		this(MeshTraitsBuilder.getDefault3D(), new MeshParameters());
	}
	
	public Mesh(MeshTraitsBuilder builder)
	{
		this(builder, new MeshParameters());
	}
	
	/**
	 * Creates an empty mesh with specific features.
	 *
	 * @param builder mesh traits builder
	 */
	public Mesh(MeshTraitsBuilder builder, MeshParameters mp)
	{
		traitsBuilder = builder;
		if (builder != null)
			traits = builder.createTraits();
		else
			traits = null;
		factory = new ElementFactory(traitsBuilder);
		triangleList = traitsBuilder.getTriangles(traits);
		nodeList = traitsBuilder.getNodes(traits);
		meshParameters = mp;
	}
	
	public MeshParameters getMeshParameters()
	{
		return meshParameters;
	}
	
	/**
	 * Adds an existing triangle to triangle list.
	 *
	 * @param t  triangle being added.
	 */
	public void add(Triangle t)
	{
		triangleList.add(t);
	}
	
	/**
	 * Removes a triangle from triangle list.
	 *
	 * @param t  triangle being removed.
	 */
	public void remove(Triangle t)
	{
		triangleList.remove(t);
		if (!(t instanceof TriangleHE))
			return;
		TriangleHE that = (TriangleHE) t;
		// Remove links to help the garbage collector
		HalfEdge e = that.getAbstractHalfEdge();
		HalfEdge last = e;
		for (int i = 0; i < 3; i++)
		{
			e = e.next();
			e.glue(null);
			last.setNext(null);
			last = e;
		}
	}
	
	/**
	 * Returns triangle list.
	 *
	 * @return triangle list.
	 */
	public Collection<Triangle> getTriangles()
	{
		return triangleList;
	}

	/**
	 * Resizes internal collections of vertices and triangles.
	 *
	 * @param triangles  desired number of triangles
	 */
	public void ensureCapacity(int triangles)
	{
		traitsBuilder.ensureCapacity(triangles, traits);
	}

	/**
	 *  Adds a vertex to vertex list.
	 */
	public void add(Vertex vertex)
	{
		nodeList.add(vertex);
	}
	
	/**
	 * Removes a vertex from vertex list.
	 *
	 * @param v  vertex being removed.
	 */
	public void remove(Vertex v)
	{
		nodeList.remove(v);
	}
	
	/**
	 * Returns vertex list.
	 *
	 * @return vertex list.
	 */
	public Collection<Vertex> getNodes()
	{
		return nodeList;
	}

	/**
	 * Tells whether nodes are stored.
	 *
	 * @return <code>true</code> if mesh was created with a <code>MeshTraitsBuilder</code>
	 * instance defining nodes, and <code>false</code> otherwise.
	 */
	public boolean hasNodes()
	{
		return traitsBuilder.hasNodes();
	}

	/**
	 * Returns the Kd-tree associated with this mesh.
	 *
	 * @return the Kd-tree associated with this mesh.
	 */
	public KdTree getKdTree()
	{
		return traitsBuilder.getKdTree(traits);
	}
	
	/**
	 * Initializes Kd-tree with a given bounding box.  This method must be called before
	 * putting any vertex into this Kd-tree.
	 *
	 * @param bbmin  coordinates of bottom-left vertex
	 * @param bbmax  coordinates of top-right vertex
	 */
	public void resetKdTree(double [] bbmin, double [] bbmax)
	{
		double [] bbox = new double[2*bbmin.length];
		for (int i = 0; i < bbmin.length; i++)
		{
			bbox[i] = bbmin[i];
			bbox[i+bbmin.length] = bbmax[i];
		}
		KdTree kdtree = traitsBuilder.getKdTree(traits);
		kdtree.setup(bbox);
	}
	
	/**
	 * Creates a triangle composed of three vertices.
	 *
	 * @param v  array of three vertices
	 * @return a new {@link Triangle} instance composed of three vertices
	 */
	public Triangle createTriangle(Vertex [] v)
	{
		assert v.length == 3;
		return factory.createTriangle(v);
	}

	/**
	 * Creates a triangle composed of four vertices, to emulate a tetrahedron.
	 *
	 * @param v  array of four vertices
	 * @return a new {@link Triangle} instance composed of four vertices
	 */
	public Triangle createTetrahedron(Vertex [] v)
	{
		assert v.length == 4;
		return factory.createTriangle(v);
	}

	/**
	 * Creates a triangle composed of three vertices.
	 *
	 * @param v0  first vertex
	 * @param v1  second vertex
	 * @param v2  third vertex
	 * @return a new {@link Triangle} instance composed of three vertices
	 */
	public Triangle createTriangle(Vertex v0, Vertex v1, Vertex v2)
	{
		return factory.createTriangle(v0, v1, v2);
	}

 	/**
	 * Clones a triangle.
	 *
	 * @param that  triangle to clone
	 * @return a new {@link Triangle} instance
	 */
	public Triangle createTriangle(Triangle that)
	{
		return factory.createTriangle(that);
	}

	/**
	 * Creates a 2D or 3D vertex.
	 *
	 * @param p  coordinates
	 * @return a new {@link Vertex} instance with this location.
	 */
	public Vertex createVertex(double [] p)
	{
		return factory.createVertex(p);
	}

	/**
	 * Creates a 2D vertex.
	 *
	 * @param u  first coordinate
	 * @param v  second coordinate
	 * @return a new {@link Vertex} instance with this location.
	 */
	public Vertex createVertex(double u, double v)
	{
		return factory.createVertex(u, v);
	}

	/**
	 * Creates a 3D vertex.
	 *
	 * @param x  first coordinate
	 * @param y  second coordinate
	 * @param z  third coordinate
	 * @return a new {@link Vertex} instance with this location.
	 */
	public Vertex createVertex(double x, double y, double z)
	{
		return factory.createVertex(x, y, z);
	}

	/**
	 * Tells whether mesh contains adjacency relations.
	 * @return <code>true</code> if mesh contains adjacency relations,
	 *         <code>false</code> otherwise.
	 */
	public boolean hasAdjacency()
	{
		return factory.hasAdjacency();
	}

	/**
	 * Build adjacency relations between triangles.
	 */
	public void buildAdjacency()
	{
		buildAdjacency(-1.0);
	}

	/**
	 * Build adjacency relations between triangles.
	 * @param minAngle  when an edge has a dihedral angle greater than this value,
	 *   it is considered as a ridge and its endpoints are treated as if they
	 *   belong to a CAD edge.  By convention, a negative value means that this
	 *   check is not performed.
	 */
	public void buildAdjacency(double minAngle)
	{
		Collection<Vertex> vertices;
		if (nodeList == null)
		{
			vertices = new LinkedHashSet<Vertex>(triangleList.size()/2);
			for (Triangle t: triangleList)
				for (Vertex v: t.vertex)
					vertices.add(v);
		}
		else
		{
			vertices = nodeList;
		}
		buildAdjacency(vertices, minAngle);
	}

	private void buildAdjacency(Collection<Vertex> vertices, double minAngle)
	{
		//  Connect all edges together
		logger.fine("Connect triangles");
		ArrayList<Triangle> newTri = new ArrayList<Triangle>();
		connectTriangles(vertices, newTri);

		//  Mark boundary edges and bind them to virtual triangles.
		logger.fine("Connect boundary triangles");
		connectBoundaryTriangles(newTri);
		
		//  Find the list of vertices which are on mesh boundary
		logger.fine("Build the list of nodes on boundaries and non-manifold edges");
		HashSet<Vertex> bndNodes = new HashSet<Vertex>();
		maxLabel = 0;
		AbstractHalfEdge ot = null;
		for (Triangle t: triangleList)
		{
			ot = t.getAbstractHalfEdge(ot);
			for (int i = 0; i < 3; i++)
			{
				ot = ot.next();
				if (ot.hasAttributes(AbstractHalfEdge.BOUNDARY | AbstractHalfEdge.NONMANIFOLD))
				{
					bndNodes.add(ot.origin());
					maxLabel = Math.max(maxLabel, ot.origin().getRef());
					bndNodes.add(ot.destination());
					maxLabel = Math.max(maxLabel, ot.destination().getRef());
				}
			}
		}

		//  Build links for non-manifold vertices
		logger.fine("Compute links for non-manifold vertices");
		Vertex [] endpoints = new Vertex[2];
		LinkedHashMap<Vertex, LinkedHashSet<Triangle>> mapNMVertexLinks = new LinkedHashMap<Vertex, LinkedHashSet<Triangle>>();
		int nrNME = 0;
		int nrFE = 0;
		for (Triangle t: triangleList)
		{
			ot = t.getAbstractHalfEdge(ot);
			for (int i = 0; i < 3; i++)
			{
				ot = ot.next();
				if (ot.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
				{
					nrNME++;
					endpoints[0] = ot.origin();
					endpoints[1] = ot.destination();
					for (int j = 0; j < 2; j++)
					{
						if (!mapNMVertexLinks.containsKey(endpoints[j]))
						{
							LinkedHashSet<Triangle> link = new LinkedHashSet<Triangle>();
							link.add((Triangle) endpoints[j].getLink());
							mapNMVertexLinks.put(endpoints[j], link);
						}
					}
					for (Iterator<AbstractHalfEdge> it = ot.fanIterator(); it.hasNext(); )
					{
						Triangle t2 = it.next().getTri();
						for (int j = 0; j < 2; j++)
						{
							LinkedHashSet<Triangle> link = mapNMVertexLinks.get(endpoints[j]);
							link.add(t2);
						}
					}
				}
				if (ot.hasAttributes(AbstractHalfEdge.BOUNDARY))
					nrFE++;
			}
		}
		int nrNMV = mapNMVertexLinks.size();
		// Replace LinkedHashSet by Triangle[], and keep only one
		// Triangle by fan. As mapNMVertexLinks is no more needed
		// after this loop, remove all references to help the
		// garbage collector.
		for (Vertex v: mapNMVertexLinks.keySet())
		{
			nrNMV++;
			LinkedHashSet<Triangle> link = mapNMVertexLinks.get(v);
			v.setLinkFan(link);
			link.clear();
			mapNMVertexLinks.put(v, null);
		}
		mapNMVertexLinks.clear();
		if (nrNMV > 0)
			logger.fine("Found "+nrNMV+" non manifold vertices");
		if (nrNME > 0)
			logger.fine("Found "+nrNME+" non manifold edges");
		if (nrFE > 0)
			logger.fine("Found "+nrFE+" free edges");
		//  If vertices are on inner boundaries and there is
		//  no ridge, change their label.
		logger.fine("Set interior nodes mutable");
		int nrJunctionPoints = 0;
		/*
		double cosMinAngle = Math.cos(Math.PI*minAngle/180.0);
		if (minAngle < 0.0)
			cosMinAngle = -2.0;
		double [][] temp = new double[4][3];
		*/
		for (Vertex v: vertices)
		{
			if (bndNodes.contains(v))
				continue;
			int label = v.getRef();
			if (v.getLink() instanceof Triangle[])
			{
				nrJunctionPoints++;
				if (label == 0)
				{
					maxLabel++;
					v.setRef(maxLabel);
				}
			}
			/*
			else if (0 != label)
			{
				//  Check for ridges
				Triangle t = (Triangle) v.getLink();
				if (detectRidge(v, cosMinAngle, t, temp))
					v.setRef(-label);
			}
			*/
		}
		if (nrJunctionPoints > 0)
			logger.info("Found "+nrJunctionPoints+" junction points");
		// Add outer triangles
		triangleList.addAll(newTri);
	}
	
	private void connectTriangles(Collection<Vertex> vertices, ArrayList<Triangle> newTri)
	{
		//  For each vertex, build the list of triangles
		//  connected to this vertex.
		HashMap<Vertex, ArrayList<Triangle>> tVertList = new HashMap<Vertex, ArrayList<Triangle>>(vertices.size());
		for (Vertex v: vertices)
			tVertList.put(v, new ArrayList<Triangle>(10));
		for (Triangle t: triangleList)
		{
			for (int i = 0; i < 3; i++)
			{
				Vertex v = t.vertex[i];
				if (v.isReadable())
				{
					ArrayList<Triangle> list = tVertList.get(v);
					list.add(t);
				}
				v.setLink(t);
			}
		}
		//  Connect all edges together
		glueIncidentHalfEdges(tVertList, newTri);
		//  Remove all references to help the garbage collector.
		for (Vertex v: vertices)
		{
			ArrayList<Triangle> list = tVertList.get(v);
			list.clear();
			tVertList.put(v, null);
		}
	}
	
	private void connectBoundaryTriangles(ArrayList<Triangle> newTri)
	{
		AbstractHalfEdge ot = null;
		AbstractHalfEdge sym = null;
		for (Triangle t: triangleList)
		{
			ot = t.getAbstractHalfEdge(ot);
			for (int i = 0; i < 3; i++)
			{
				ot = ot.next();
				if (!ot.hasSymmetricEdge())
				{
					ot.setAttributes(AbstractHalfEdge.BOUNDARY);
					Triangle adj = factory.createTriangle(outerVertex, ot.destination(), ot.origin());
					newTri.add(adj);
					adj.setAttributes(AbstractHalfEdge.OUTER);
					adj.setReadable(false);
					adj.setWritable(false);
					sym = adj.getAbstractHalfEdge(sym);
					sym.setAttributes(AbstractHalfEdge.BOUNDARY);
					ot.glue(sym);
				}
				else if (ot.hasAttributes(AbstractHalfEdge.OUTER))
				{
					if (sym == null)
						sym = t.getAbstractHalfEdge(sym);
					sym = ot.sym(sym);
					if (!sym.hasAttributes(AbstractHalfEdge.OUTER))
					{
						ot.setAttributes(AbstractHalfEdge.BOUNDARY);
						sym.setAttributes(AbstractHalfEdge.BOUNDARY);
					}
				}
			}
		}
	}

	private final void glueIncidentHalfEdges(HashMap<Vertex, ArrayList<Triangle>> tVertList, ArrayList<Triangle> newTri)
	{
		Triangle.List markedTri = new Triangle.List();
		AbstractHalfEdge ot = null;
		AbstractHalfEdge ot2 = null;
		AbstractHalfEdge [] work = new AbstractHalfEdge[3];
		for (Map.Entry<Vertex, ArrayList<Triangle>> e: tVertList.entrySet())
		{
			//  Mark all triangles having v as vertex
			Vertex v = e.getKey();
			ArrayList<Triangle> neighTriList = e.getValue();
			if (neighTriList == null)
				continue;
			for (Triangle t: neighTriList)
				markedTri.add(t);
			//  Loop on all edges incident to v
			for (Triangle t: neighTriList)
			{
				ot = v.getIncidentAbstractHalfEdge(t, ot);
				// Skip this edge if adjacency relations already exist, 
				if (ot.hasSymmetricEdge())
					continue;
				Vertex v2 = ot.destination();
				ArrayList<Triangle> neighTriList2 = tVertList.get(v2);
				if (neighTriList2 == null)
					continue;
				// Edge (v,v2) has not yet been processed.
				// List of triangles incident to v2.
				boolean manifold = true;
				// Ensure that work[0] and work[1] are non null to avoid
				// tests in glueNonManifoldHalfEdges
				if (work[0] == null)
					work[0] = t.getAbstractHalfEdge(work[0]);
				if (work[1] == null)
					work[1] = t.getAbstractHalfEdge(work[1]);
				for (Triangle t2: neighTriList2)
				{
					if (t == t2 || !markedTri.contains(t2))
						continue;
					// t2 contains v and v2, we now look for an edge
					// (v,v2) or (v2,v)
					ot2 = v2.getIncidentAbstractHalfEdge(t2, ot2);
					if (manifold && ot2.destination() == v && !ot.hasSymmetricEdge() && !ot2.hasSymmetricEdge())
					{
						// This edge seems to be manifold.
						// It may become non manifold later when
						// other neighbours are processed.
						ot.glue(ot2);
						continue;
					}
					manifold = false;
					if (ot2.destination() != v)
						ot2 = ot2.prev();
					// We are sure now that ot2 == (v,v2) or (v2,v)
					glueNonManifoldHalfEdges(v, v2, ot, ot2, work, newTri);
				}
				if (logger.isLoggable(Level.FINE) && !manifold)
				{
					int cnt = 0;
					for (Iterator<AbstractHalfEdge> it = ot.fanIterator(); it.hasNext(); it.next())
						cnt++;
					logger.fine("Non-manifold edge: "+v+" "+v2+" "+" connected to "+cnt+" fans");
				}
			}
			//  Unmark adjacent triangles
			markedTri.clear();
		}
	}

	private final void glueNonManifoldHalfEdges(Vertex v, Vertex v2, AbstractHalfEdge ot, AbstractHalfEdge ot2, AbstractHalfEdge [] work, ArrayList<Triangle> newTri)
	{
		assert v == ot.origin() && v2 == ot.destination();
		assert (v == ot2.origin() && v2 == ot2.destination()) || (v2 == ot2.origin() && v == ot2.destination());
		if (!ot.hasSymmetricEdge())
		{
			// ot has no symmetric edge yet; this happens only if this edge has
			// not been processed yet and ot and ot2 have incompatible orientations,
			// i.e. ot2 = (v, v2)
			assert v == ot2.origin() && v2 == ot2.destination();
			// Link ot to a virtual triangle.
			work[0] = bindToVirtualTriangle(ot, work[0]);
			newTri.add(work[0].getTri());
			// Create an empty cycle
			work[1] = work[0].prev(work[1]);
			work[0].next().glue(work[1]);
		}
		else if (!ot.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
		{
			// ot was already linked to another edge, but it is in fact a
			// non-manifold edge.  Previous adjacency relation is removed
			// and both triangles are linked to virtual triangles.
			// First, store symmetric edge in work[0]
			work[0] = ot.sym(work[0]);

			// Link ot to a virtual triangle
			work[1] = bindToVirtualTriangle(ot, work[1]);
			newTri.add(work[1].getTri());
			// Link work[0] to another virtual triangle
			work[2] = bindToVirtualTriangle(work[0], work[2]);
			newTri.add(work[2].getTri());
			// Create an initial cycle
			work[2] = work[2].next();
			work[1] = work[1].prev();
			work[1].glue(work[2]);
			work[2] = work[2].next();
			work[1] = work[1].prev();
			work[1].glue(work[2]);
		}

		assert !ot2.hasSymmetricEdge();
		// Link ot2 to a virtual triangle
		work[0] = bindToVirtualTriangle(ot2, work[0]);
		newTri.add(work[0].getTri());
		// Add ot2 to existing cycle
		work[0] = ot.sym(work[0]);
		work[0] = work[0].next();
		work[1] = work[0].sym(work[1]);
		ot2 = ot2.sym();
		ot2 = ot2.prev();
		ot2.glue(work[0]);
		ot2 = ot2.prev();
		ot2.glue(work[1]);
	}
	
	private final AbstractHalfEdge bindToVirtualTriangle(AbstractHalfEdge ot, AbstractHalfEdge sym)
	{
		Triangle t = factory.createTriangle(outerVertex, ot.destination(), ot.origin());
		t.setAttributes(AbstractHalfEdge.OUTER);
		t.setReadable(false);
		t.setWritable(false);
		sym = t.getAbstractHalfEdge(sym);
		ot.glue(sym);
		ot.setAttributes(AbstractHalfEdge.NONMANIFOLD);
		sym.setAttributes(AbstractHalfEdge.NONMANIFOLD);
		return sym;
	}

	private final boolean detectRidge(Vertex v, double cosMinAngle, Triangle t, double [][] temp)
	{
		AbstractHalfEdge ot = v.getIncidentAbstractHalfEdge(t, null);
		AbstractHalfEdge sym = t.getAbstractHalfEdge();
		if (!sameGroup(ot, sym))
			return false;
		// Now check for coplanarity
		if (cosMinAngle < -1.0)
			return true;
		Vertex first = ot.destination();
		while (true)
		{
			Vertex d = ot.destination();
			if (d != outerVertex && 0 != d.getRef())
			{
				sym = ot.sym(sym);
				Matrix3D.computeNormal3D(v.getUV(), d.getUV(), ot.apex().getUV(), temp[0], temp[1], temp[2]);
				Matrix3D.computeNormal3D(d.getUV(), v.getUV(), sym.apex().getUV(), temp[0], temp[1], temp[3]);
				double angle = Matrix3D.prodSca(temp[2], temp[3]);
				if (angle > -cosMinAngle)
					return false;
			}
			ot = ot.nextOrigin();
			if (ot.destination() == first)
				break;
		}
		return true;
	}

	// Check that all incident triangles belong to the same group
	private final boolean sameGroup(AbstractHalfEdge ot, AbstractHalfEdge sym)
	{
		Vertex first = ot.destination();
		int id = ot.getTri().getGroupId();
		while (true)
		{
			Vertex d = ot.destination();
			if (d != outerVertex && 0 != d.getRef())
			{
				if (id != ot.getTri().getGroupId())
					return false;
				sym = ot.sym(sym);
				if (id != sym.getTri().getGroupId())
					return false;
			}
			ot = ot.nextOrigin();
			if (ot.destination() == first)
				break;
		}
		return true;
	}
	
	/**
	 * Sets an unused boundary reference on a vertex.
	 */
	public void setRefVertexOnboundary(Vertex v)
	{
		maxLabel++;
		v.setRef(maxLabel);
	}
	
	/**
	 * Returns square distance between 2 vertices.
	 */
	public double distance2(Vertex start, Vertex end, Vertex vm)
	{
		double [] x1 = start.getUV();
		double [] x2 = end.getUV();
		assert x1.length == 3;
		double dx = x1[0] - x2[0];
		double dy = x1[1] - x2[1];
		double dz = x1[2] - x2[2];
		return dx*dx + dy*dy + dz*dz;
	}
	
	public double [] getBounds(Vertex v)
	{
		double [] ret = new double[]{1.0, 1.0, 1.0};
		return ret;
	}
	
	/**
	 * Checks whether an edge can be contracted.
	 *
	 * @param e   edge to be checked
	 * @param v   the resulting vertex
	 * @return <code>true</code> if this edge can be contracted into the single vertex n, <code>false</code> otherwise.
	 */
	public boolean canCollapseEdge(AbstractHalfEdge e, Vertex v)
	{
		return e.canCollapse(v);
	}
	
	/**
	 * Contracts an edge.
	 *
	 * @param e   edge to contract
	 * @param v   the resulting vertex
	 * @return edge starting from <code>n</code> and with the same apex
	 * @throws IllegalArgumentException if edge belongs to an outer triangle,
	 * because there would be no valid return value.  User must then run this
	 * method against symmetric edge, this is not done automatically.
	 */
	public AbstractHalfEdge edgeCollapse(AbstractHalfEdge e, Vertex v)
	{
		return e.collapse(this, v);
	}
	
	/**
	 * Splits an edge.  This is the opposite of {@link #edgeCollapse}.
	 *
	 * @param e   edge being splitted
	 * @param v   the resulting vertex
	 */
	public AbstractHalfEdge vertexSplit(AbstractHalfEdge e, Vertex v)
	{
		return e.split(this, v);
	}
	
	/**
	 * Swaps an edge.
	 *
	 * @return swapped edge, origin and apical vertices are the same as in original edge
	 * @throws IllegalArgumentException if edge is on a boundary or belongs
	 * to an outer triangle.
	 */
	public AbstractHalfEdge edgeSwap(AbstractHalfEdge e)
	{
		return e.swap();
	}
	
	/**
	 * Checks whether this mesh is valid.
	 * This routine returns <code>isValid(true)</code>.
	 *
	 * @see #isValid(boolean)
	 */
	public boolean isValid()
	{
		return isValid(true);
	}
	
	/**
	 * Checks whether this mesh is valid.
	 * This routine can be called at any stage, even before boundary
	 * edges have been enforced.  In this case, some tests must be
	 * removed because they do not make sense.
	 *
	 * @param constrained  <code>true</code> if mesh is constrained.
	 */
	public boolean isValid(boolean constrained)
	{
		for (Triangle t: triangleList)
		{
			if (t.vertex[0] == t.vertex[1] || t.vertex[1] == t.vertex[2] || t.vertex[2] == t.vertex[0])
			{
				logger.severe("Duplicate vertices: "+t);
				return false;
			}
			if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
			{
				if (constrained && factory.hasAdjacency())
				{
					if (!t.hasAttributes(AbstractHalfEdge.OUTER))
					{
						logger.severe("Triangle should be outer: "+t);
						return false;
					}
				}
			}
			for (int i = 0; i < 3; i++)
			{
				Vertex v = t.vertex[i];
				if (v.getLink() == null)
					continue;
				if (v.getLink() instanceof Triangle)
				{
					Triangle t2 = (Triangle) v.getLink();
					if (t2.vertex[0] != v && t2.vertex[1] != v && t2.vertex[2] != v)
					{
						logger.severe("Vertex "+v+" linked to "+t2);
						return false;
					}
					if (!triangleList.contains(t2))
					{
						logger.severe("Vertex "+v+" linked to removed triangle: "+t2);
						return false;
					}
				}
				else
				{
					// Check that all linked triangles are
					// still present in mesh
					Triangle [] links = (Triangle []) v.getLink();
					for (Triangle t2: links)
					{
						if (!triangleList.contains(t2))
						{
							logger.severe("Vertex "+v+" linked to removed triangle: "+t2);
							return false;
						}
					}
				}
			}
			if (!checkVirtualHalfEdges(t))
				return false;
			if (!checkHalfEdges(t))
				return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private boolean checkVirtualHalfEdges(Triangle t)
	{
		if (!t.traitsBuilder.hasCapability(TriangleTraitsBuilder.VIRTUALHALFEDGE))
			return true;
		VirtualHalfEdge ot = new VirtualHalfEdge();
		VirtualHalfEdge sym = new VirtualHalfEdge();
		ot.bind((TriangleVH) t);
		boolean isOuter = ot.hasAttributes(AbstractHalfEdge.OUTER);
		for (int i = 0; i < 3; i++)
		{
			ot.next();
			if (isOuter != ot.hasAttributes(AbstractHalfEdge.OUTER))
			{
				logger.severe("Inconsistent outer state: "+ot);
				return false;
			}
			if (!ot.hasSymmetricEdge())
				continue;
			sym = ot.sym(sym);
			sym.sym();
			if (sym.getTri() != ot.getTri())
			{
				logger.severe("Wrong adjacency relation: ");
				logger.severe(" adj1: "+ot);
				logger.severe(" adj2: "+sym);
				return false;
			}
			sym.sym();
			if (ot.hasAttributes(AbstractHalfEdge.BOUNDARY) != sym.hasAttributes(AbstractHalfEdge.BOUNDARY) || ot.hasAttributes(AbstractHalfEdge.NONMANIFOLD) != sym.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
			{
				logger.severe("Inconsistent boundary or nonmanifold flags");
				logger.severe(" "+ot);
				logger.severe(" "+sym);
				return false;
			}
			if (ot.hasAttributes(AbstractHalfEdge.BOUNDARY | AbstractHalfEdge.NONMANIFOLD) && sym.hasAttributes(AbstractHalfEdge.OUTER) != !isOuter)
			{
				logger.severe("Inconsistent outer flags");
				logger.severe(" "+ot);
				logger.severe(" "+sym);
				return false;
			}
			if (!triangleList.contains(sym.getTri()))
			{
				logger.severe("Triangle not present in mesh: "+sym.getTri());
				logger.severe("Linked from "+ot);
				return false;
			}
			Vertex v1 = ot.origin();
			Vertex v2 = ot.destination();
			if (!isOuter)
			{
				if (sym.origin() != v2 || sym.destination() != v1)
				{
					logger.severe("Vertex mismatch in adjacency relation: ");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				continue;
			}
			// triangle is outer
			if (ot.hasAttributes(AbstractHalfEdge.BOUNDARY) && !outerTrianglesAreConnected)
			{
				// Edge is manifold
				// next() and prev() must not be linked to other edges
				ot.next();
				if (ot.hasSymmetricEdge())
				{
					logger.severe("Outer edge: should not be linked to another edge: "+ot);
					return false;
				}
				ot.next();
				if (ot.hasSymmetricEdge())
				{
					logger.severe("Outer edge: should not be linked to another edge: "+ot);
					return false;
				}
				ot.next();
			}
			else if (ot.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
			{
				if (!(v1.getLink() instanceof Triangle[]))
				{
					logger.severe("Multiple edges: endpoint must be non-manifold: "+v1);
					logger.severe(" "+ot);
					return false;
				}
				if (!(v2.getLink() instanceof Triangle[]))
				{
					logger.severe("Multiple edges: endpoint must be non-manifold: "+v2);
					logger.severe(" "+ot);
					return false;
				}
				// next() and prev() must point to other non-manifold edges
				ot.next();
				if (!ot.hasSymmetricEdge())
				{
					logger.severe("Multiple edge: must be linked to another edge: "+ot);
					return false;
				}
				VirtualHalfEdge.symOTri(ot, sym);
				sym.next();
				if (!sym.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
				{
					logger.severe("Multiple edges: linked to a non-manifold edge");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				if (!ot.hasAttributes(AbstractHalfEdge.OUTER) || !sym.hasAttributes(AbstractHalfEdge.OUTER))
				{
					logger.severe("Multiple edges: linked to an inner edge");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				if (!triangleList.contains(sym.getTri()))
				{
					logger.severe("Multiple edges: Triangle not present in mesh: "+sym.getTri());
					logger.severe("Linked from "+this);
					return false;
				}
				if (!((sym.origin() == v1 && sym.destination() == v2) || (sym.origin() == v2 && sym.destination() == v1)))
				{
					logger.severe("Multiple edges: vertex mismatch in adjacency relation: ");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				ot.next();
				if (!ot.hasSymmetricEdge())
				{
					logger.severe("Multiple edge: must be linked to another edge: "+ot);
					return false;
				}
				VirtualHalfEdge.symOTri(ot, sym);
				sym.prev();
				if (!sym.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
				{
					logger.severe("Multiple edges: linked to a non-manifold edge");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				if (!ot.hasAttributes(AbstractHalfEdge.OUTER) || !sym.hasAttributes(AbstractHalfEdge.OUTER))
				{
					logger.severe("Multiple edges: linked to an inner edge");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
				if (!triangleList.contains(sym.getTri()))
				{
					logger.severe("Multiple edges: Triangle not present in mesh: "+sym.getTri());
					logger.severe("Linked from "+this);
					return false;
				}
				if (!((sym.origin() == v1 && sym.destination() == v2) || (sym.origin() == v2 && sym.destination() == v1)))
				{
					logger.severe("Multiple edges: vertex mismatch in adjacency relation: ");
					logger.severe(" "+ot);
					logger.severe(" "+sym);
					return false;
				}
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private boolean checkHalfEdges(Triangle t)
	{
		if (!t.traitsBuilder.hasCapability(TriangleTraitsBuilder.HALFEDGE))
			return true;
		HalfEdge e = (HalfEdge) t.getAbstractHalfEdge();
		boolean isOuter = e.hasAttributes(AbstractHalfEdge.OUTER);
		for (int i = 0; i < 3; i++)
		{
			e = e.next();
			if (isOuter != e.hasAttributes(AbstractHalfEdge.OUTER))
			{
				logger.severe("Inconsistent outer state: "+e);
				return false;
			}
			if (!e.hasSymmetricEdge())
				continue;
			HalfEdge f = e.sym();
			if (f.sym() != e)
			{
				logger.severe("Wrong adjacency relation: ");
				logger.severe(" adj1: "+e);
				logger.severe(" adj2: "+f);
				return false;
			}
			if (f.hasAttributes(AbstractHalfEdge.BOUNDARY) != e.hasAttributes(AbstractHalfEdge.BOUNDARY) || f.hasAttributes(AbstractHalfEdge.NONMANIFOLD) != e.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
			{
				logger.severe("Inconsistent boundary or nonmanifold flags");
				logger.severe(" "+e);
				logger.severe(" "+f);
				return false;
			}
			if (e.hasAttributes(AbstractHalfEdge.BOUNDARY | AbstractHalfEdge.NONMANIFOLD) && f.hasAttributes(AbstractHalfEdge.OUTER) != !isOuter)
			{
				logger.severe("Inconsistent outer flags");
				logger.severe(" "+e);
				logger.severe(" "+f);
				return false;
			}
			if (!triangleList.contains(f.getTri()))
			{
				logger.severe("Triangle not present in mesh: "+f.getTri());
				logger.severe("Linked from "+e);
				return false;
			}
			Vertex v1 = e.origin();
			Vertex v2 = e.destination();
			if (!isOuter)
			{
				if (f.origin() != v2 || f.destination() != v1)
				{
					logger.severe("Vertex mismatch in adjacency relation: ");
					logger.severe(" "+e);
					logger.severe(" "+f);
					return false;
				}
				continue;
			}
			// triangle is outer
			if (e.hasAttributes(AbstractHalfEdge.BOUNDARY) && !outerTrianglesAreConnected)
			{
				// Edge e is manifold
				// next() and prev() must not be linked to other edges
				AbstractHalfEdge g = e.next();
				if (g.hasSymmetricEdge())
				{
					logger.severe("Outer edge: should not be linked to another edge: "+g);
					return false;
				}
				g = e.prev();
				if (g.hasSymmetricEdge())
				{
					logger.severe("Outer edge: should not be linked to another edge: "+g);
					return false;
				}
			}
			else if (e.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
			{
				if (!(v1.getLink() instanceof Triangle[]))
				{
					logger.severe("Multiple edges: endpoint must be non-manifold: "+v1);
					logger.severe(" "+e);
					return false;
				}
				if (!(v2.getLink() instanceof Triangle[]))
				{
					logger.severe("Multiple edges: endpoint must be non-manifold: "+v2);
					logger.severe(" "+e);
					return false;
				}
				// next() and prev() must point to other non-manifold edges
				AbstractHalfEdge g = e.next();
				if (!g.hasSymmetricEdge())
				{
					logger.severe("Multiple edge: must be linked to another edge: "+g);
					return false;
				}
				AbstractHalfEdge h = e.prev();
				if (!h.hasSymmetricEdge())
				{
					logger.severe("Multiple edge: must be linked to another edge: "+h);
					return false;
				}
				g = g.sym().next();
				h = h.sym().prev();
				if (!g.hasAttributes(AbstractHalfEdge.NONMANIFOLD) || !h.hasAttributes(AbstractHalfEdge.NONMANIFOLD))
				{
					logger.severe("Multiple edges: linked to a non-manifold edge");
					logger.severe(" "+f);
					logger.severe(" "+g);
					logger.severe(" "+h);
					return false;
				}
				if (!g.hasAttributes(AbstractHalfEdge.OUTER) || !h.hasAttributes(AbstractHalfEdge.OUTER))
				{
					logger.severe("Multiple edges: linked to an inner edge");
					logger.severe(" "+f);
					logger.severe(" "+g);
					logger.severe(" "+h);
					return false;
				}
				if (!triangleList.contains(g.getTri()))
				{
					logger.severe("Multiple edges: Triangle not present in mesh: "+g.getTri());
					logger.severe("Linked from "+f);
					return false;
				}
				if (!triangleList.contains(h.getTri()))
				{
					logger.severe("Multiple edges: Triangle not present in mesh: "+h.getTri());
					logger.severe("Linked from "+f);
					return false;
				}
				if (!((g.origin() == v1 && g.destination() == v2) || (g.origin() == v2 && g.destination() == v1)))
				{
					logger.severe("Multiple edges: vertex mismatch in adjacency relation: ");
					logger.severe(" "+e);
					logger.severe(" "+g);
					return false;
				}
				if (!((h.origin() == v1 && h.destination() == v2) || (h.origin() == v2 && h.destination() == v1)))
				{
					logger.severe("Multiple edges: vertex mismatch in adjacency relation: ");
					logger.severe(" "+e);
					logger.severe(" "+h);
					return false;
				}
			}
		}
		return true;
	}
	
	// Useful for debugging
	/*  Following imports must be moved at top.
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import gnu.trove.TObjectIntHashMap;

	public void printMesh()
	{
		System.out.println("Mesh:");
		for (Triangle t: triangleList)
			System.out.println(""+t);
		System.out.println("Outer Vertex: "+outerVertex);
	}
	public void writeUNV(String file)
	{
		String cr=System.getProperty("line.separator");
		PrintWriter out;
		try {
			if (file.endsWith(".gz") || file.endsWith(".GZ"))
				out = new PrintWriter(new GZIPOutputStream(new FileOutputStream(file)));
			else
				out = new PrintWriter(new FileOutputStream(file));
			out.println("    -1"+cr+"  2411");
			HashSet<Vertex> nodeset = new HashSet<Vertex>();
			for (Triangle t: triangleList)
			{
				if (t.hasAttributes(AbstractHalfEdge.OUTER))
					continue;
				if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
					continue;
				nodeset.add(t.vertex[0]);
				nodeset.add(t.vertex[1]);
				nodeset.add(t.vertex[2]);
			}
			int count = 0;
			TObjectIntHashMap<Vertex> labels = new TObjectIntHashMap<Vertex>(nodeset.size());
			for(Vertex node: nodeset)
			{
				count++;
				labels.put(node, count);
				double [] uv = node.getUV();
				out.println(count+"         1         1         1");
				if (uv.length == 2)
					out.println(""+uv[0]+" "+uv[1]+" 0.0");
				else
					out.println(""+uv[0]+" "+uv[1]+" "+uv[2]);
			}
			out.println("    -1");
			out.println("    -1"+cr+"  2412");
			count = 0;
			for (Triangle t: triangleList)
			{
				if (t.hasAttributes(AbstractHalfEdge.OUTER))
					continue;
				if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
					continue;
				count++;
				out.println(""+count+"        91         1         1         1         3");
				for(int i = 0; i < 3; i++)
				{
					int nodelabel = labels.get(t.vertex[i]);
					out.print(" "+nodelabel);
				}
				out.println("");
			}
			out.println("    -1");
			out.close();
		} catch (FileNotFoundException e)
		{
			logger.severe(e.toString());
			e.printStackTrace();
		} catch (IOException e)
		{
			logger.severe(e.toString());
			e.printStackTrace();
		}
	}
	public void writeMesh(String file)
	{
		String cr=System.getProperty("line.separator");
		PrintWriter out;
		try {
			if (file.endsWith(".gz") || file.endsWith(".GZ"))
				out = new PrintWriter(new GZIPOutputStream(new FileOutputStream(file)));
			else
				out = new PrintWriter(new FileOutputStream(file));
			out.println("MeshVersionFormatted 1"+cr+"Dimension"+cr+"3");
			HashSet<Vertex> nodeset = new HashSet<Vertex>();
			for(Triangle t: triangleList)
			{
				if (t.hasAttributes(AbstractHalfEdge.OUTER))
					continue;
				if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
					continue;
				nodeset.add(t.vertex[0]);
				nodeset.add(t.vertex[1]);
				nodeset.add(t.vertex[2]);
			}
			int count = 0;
			TObjectIntHashMap<Vertex> labels = new TObjectIntHashMap<Vertex>(nodeset.size());
			out.println("Vertices"+cr+nodeset.size());
			for(Vertex node: nodeset)
			{
				count++;
				labels.put(node, count);
				double [] uv = node.getUV();
				if (uv.length == 2)
					out.println(""+uv[0]+" "+uv[1]+" 0.0 0");
				else
					out.println(""+uv[0]+" "+uv[1]+" "+uv[2]+" 0");
			}
			count = 0;
			for(Triangle t: triangleList)
			{
				if (t.hasAttributes(AbstractHalfEdge.OUTER))
					continue;
				if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
					continue;
				count++;
			}
			out.println(cr+"Triangles"+cr+count);
			count = 0;
			for(Triangle t: triangleList)
			{
				if (t.hasAttributes(AbstractHalfEdge.OUTER))
					continue;
				if (t.vertex[0] == outerVertex || t.vertex[1] == outerVertex || t.vertex[2] == outerVertex)
					continue;
				count++;
				for(int i = 0; i < 3; i++)
				{
					int nodelabel = labels.get(t.vertex[i]);
					out.print(nodelabel+" ");
				}
				out.println("1");
			}
			out.println(cr+"End");
			out.close();
		} catch (FileNotFoundException e)
		{
			logger.severe(e.toString());
			e.printStackTrace();
		} catch (IOException e)
		{
			logger.severe(e.toString());
			e.printStackTrace();
		}
	}
	*/
	
}
