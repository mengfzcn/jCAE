/* jCAE stand for Java Computer Aided Engineering. Features are : Small CAD
   modeler, Finite element mesher, Plugin architecture.

    Copyright (C) 2005, by EADS CRC

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


package org.jcae.mesh;

import org.jcae.mesh.oemm.OEMM;
import org.jcae.mesh.oemm.Storage;
import org.jcae.mesh.oemm.MeshReader;
import org.jcae.mesh.amibe.ds.Mesh;
import org.jcae.mesh.java3d.Viewer;
import org.jcae.viewer3d.OEMMViewer;

/**
 * This class illustrates how to perform quality checks.
 */
public class MeshOEMMView
{
	public static void main(String args[])
	{
		if (args.length < 1)
		{
			System.out.println("Usage: MeshOEMMView dir");
			System.exit(0);
		}
		String dir=args[0];
		OEMM oemm = Storage.readOEMMStructure(dir);
		MeshReader mr = new MeshReader(oemm);
		Mesh mesh = mr.buildWholeMesh();
		Viewer view=new Viewer();
		view.addBranchGroup(OEMMViewer.bgOEMM(oemm, true));
		view.addBranchGroup(OEMMViewer.meshOEMM(mesh));
		view.zoomTo(); 
		view.setVisible(true);
	}
	
}