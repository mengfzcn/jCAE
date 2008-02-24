/* jCAE stand for Java Computer Aided Engineering. Features are : Small CAD
   modeler, Finite element mesher, Plugin architecture.

    Copyright (C) 2006, by EADS CRC
    Copyright (C) 2007, by EADS France

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


package org.jcae.mesh.bora.ds;

import org.jcae.mesh.bora.xmldata.BModelWriter;
import org.jcae.mesh.bora.xmldata.Storage;
import org.jcae.mesh.cad.CADShapeFactory;
import org.jcae.mesh.cad.CADShape;
import org.jcae.mesh.cad.CADShapeEnum;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Stack;
import java.util.Iterator;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CAD object.
 */
public class BModel
{
	private static Logger logger=Logger.getLogger(BModel.class.getName());
	//   Next available index
	private static int freeIndex = 1;
	//   Model number
	private int id;
	//   CAD graph
	private BCADGraph cad;
	//   List of submeshes
	private Collection<BSubMesh> submesh = new ArrayList<BSubMesh>();
	//   Geometry file
	private String cadFile;
	//   Output variables
	private String xmlDir;
	private String xmlFile = "model";
	private String xmlBrepDir;
	//   List of all constraints
	private Collection<Constraint> allConstraints = new LinkedHashSet<Constraint>();
	//   Internal state
	private int state = INPUT;
	//   Valid state values
	private static final int INPUT         = 0;
	private static final int CONSTRAINTS   = 1;
	private static final int TESSELLATION  = 2;

	/**
	 * Bind a CAD representation to a disk directory.
	 */
	public BModel (String brep, String out)
	{
		id = freeIndex;
		if (logger.isLoggable(Level.FINE))
			logger.fine("Building model "+id+" from "+brep+" into "+out);
		freeIndex++;
		CADShapeFactory factory = CADShapeFactory.getFactory();
		xmlDir = out;
		File xmlDirF = new File(xmlDir);
		xmlDirF.mkdirs();
		if(!xmlDirF.exists() || !xmlDirF.isDirectory())
		{
			System.out.println("Cannot write to "+xmlDir);
			return;
		}

		cadFile = (new File(brep)).getAbsoluteFile().getPath();
		xmlBrepDir = relativize(new File(brep).getAbsoluteFile().getParentFile(), new File(xmlDir).getAbsoluteFile()).getPath();
		// CAD graph
		cad = new BCADGraph(this, factory.newShape(brep));
	}
	
	public static void reset()
	{
		freeIndex = 1;
		BDiscretization.nextId = -1;
	}

	public int getId()
	{
		return id;
	}

	public String getCADFile()
	{
		return cadFile;
	}

	public String getRelativeCADDir()
	{
		return xmlBrepDir;
	}

	public CADShape getCADShape()
	{
		return cad.getRootCell().getShape();
	}

	public String getOutputDir()
	{
		return xmlDir;
	}

	public String getOutputDir(BDiscretization d)
	{
		String ret = xmlDir+File.separator;
		CADShapeEnum cse = d.getGraphCell().getType();
		if (cse == CADShapeEnum.EDGE)
			ret += "1";
		else if (cse == CADShapeEnum.FACE)
			ret += "2";
		else if (cse == CADShapeEnum.SOLID)
			ret += "3";
		else
			throw new RuntimeException("Invalid CADShapeEnum: "+cse);
		return ret+"d"+d.getId();
	}

	public String getOutputFile()
	{
		return xmlFile;
	}

	public BCADGraph getGraph()
	{
		return cad;
	}

	public Collection<Constraint> getConstraints()
	{
		return allConstraints;
	}

	private static File relativize(File file, File reference)
	{
		File current = file;
		Stack<String> l = new Stack<String>();
		while (current != null && !current.equals(reference))
		{
			l.push(current.getName());
			current = current.getParentFile();
		}
		if (l.isEmpty())
			return new File(".");
		else if (current == null)
			return file;
		else
		{
			current = new File(l.pop().toString());
			while(!l.isEmpty())
				current = new File(current, l.pop().toString());
			return current;
		}
	}

	public BSubMesh newMesh()
	{
		if (state != INPUT)
			throw new RuntimeException("BModel.newMesh() cannot be called after model has been computed");
		BSubMesh ret = new BSubMesh(this, cad.getFreeIndex());
		submesh.add(ret);
		return ret;
	}

	public Iterator<BSubMesh> getSubMeshIterator()
	{
		return submesh.iterator();
	}

	/**
	 * Prints the list of geometrical elements.
	 */
	public void printShapes()
	{
		cad.printShapes();
	}

	public void addConstraint(Constraint cons)
	{
		if (state != INPUT)
			throw new RuntimeException("Constraints cannot be added after model has been computed");
		allConstraints.add(cons);
	}

	/**
  	 * Combines all hypothesis.
	 */
	public void computeConstraints()
	{
		logger.info("Compute constraints");
		BCADGraphCell root = cad.getRootCell();
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.SOLID); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			cell.addImplicitConstraints(CADShapeEnum.FACE, true);
			cell.addImplicitConstraints(CADShapeEnum.EDGE, false);
			cell.addImplicitConstraints(CADShapeEnum.VERTEX, false);
		}
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.FACE); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			cell.addImplicitConstraints(CADShapeEnum.EDGE, true);
			cell.addImplicitConstraints(CADShapeEnum.VERTEX, false);
		}
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.EDGE); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			cell.addImplicitConstraints(CADShapeEnum.VERTEX, true);
		}
		state = CONSTRAINTS;
		// Update constraints
		BModelWriter.writeObject(this);
	}

	/**
	 * Combines all hypothesis and computes meshes.
	 */
	public void compute()
	{
		if (state == INPUT)
			computeConstraints();
		else if (state != CONSTRAINTS)
			throw new RuntimeException("Invalid state: "+state);
		BCADGraphCell root = cad.getRootCell();
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.VERTEX); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
			{
				BDiscretization d = itd.next();
				d.discretize();
			}
		}
		logger.info("Discretize edges");
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.EDGE); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
			{
				BDiscretization d = itd.next();
				d.discretize();
				Storage.writeEdge(d, getOutputDir(d));
			}
		}
		logger.info("Discretize faces");
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.FACE); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
			{
				BDiscretization d = itd.next();
				d.discretize();
				Storage.writeFace(d, getOutputDir(d));
			}
		}
		logger.info("Discretize solids");
		for (Iterator<BCADGraphCell> its = root.shapesExplorer(CADShapeEnum.SOLID); its.hasNext(); )
		{
			BCADGraphCell cell = its.next();
			for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
			{
				BDiscretization d = itd.next();
				d.discretize();
				Storage.writeSolid(d, getOutputDir(d));
			}
		}
		state = TESSELLATION;
		logger.info("Discretization finished");
	}

	/**
	 * Prints all hypothesis applied to any submesh.
	 */
	public void printAllHypothesis()
	{
		System.out.println("List of hypothesis");
		for (Iterator<Constraint> it = allConstraints.iterator(); it.hasNext(); )
		{
			Constraint cons = it.next();
			Hypothesis h = cons.getHypothesis();
			System.out.println(" + ("+Integer.toHexString(h.hashCode())+") "+h);
		}
		System.out.println("End list");
	}

	/**
	 * Prints user defined constraints on the submeshes.
	 */
	public void printSubmeshesConstraints()
	{
		System.out.println("List of submeshes");
		for (Iterator<BSubMesh> it = submesh.iterator(); it.hasNext(); )
		{
			BSubMesh subm = it.next();
			System.out.println(" Submesh ("+subm.getId()+") ");
			for (Iterator<Constraint> itc = subm.getConstraints().iterator(); itc.hasNext(); )
				System.out.println("    "+itc.next());
		}
		System.out.println("End list");
	}

	/**
	 * Prints used discretizations on the submeshes.
	 */
	public void printSubmeshesDiscretizations()
	{
		System.out.println("List of discretizations on submeshes");
		for (Iterator<BSubMesh> it = submesh.iterator(); it.hasNext(); )
		{
			BSubMesh subm = it.next();
			System.out.println(" Discretizations used on submesh ("+subm.getId()+") ");
			subm.printSubmeshDiscretizations();
		}
		System.out.println("End list");
	}

	/**
	 * Print all discretizations.
	 */
	public void printDiscretizations()
	{
		System.out.println("List of discretizations");
		BCADGraphCell root = cad.getRootCell();
		StringBuilder indent = new StringBuilder();
		for (Iterator<CADShapeEnum> itcse = CADShapeEnum.iterator(CADShapeEnum.VERTEX, CADShapeEnum.COMPOUND); itcse.hasNext(); )
		{
			CADShapeEnum cse = itcse.next();
			CADShapeEnum normalChildType;
			if (cse == CADShapeEnum.EDGE)
				normalChildType = CADShapeEnum.VERTEX;
			else if (cse == CADShapeEnum.FACE)
				normalChildType = CADShapeEnum.EDGE;
			else if (cse == CADShapeEnum.SOLID)
				normalChildType = CADShapeEnum.FACE;
			else 
				normalChildType = CADShapeEnum.VERTEX;

			if ((cse == CADShapeEnum.WIRE) || (cse == CADShapeEnum.SHELL) ||
			    (cse == CADShapeEnum.COMPSOLID) || (cse == CADShapeEnum.COMPOUND))
			    continue;

			String tab = indent.toString();
			for (Iterator<BCADGraphCell> itp = root.shapesExplorer(cse); itp.hasNext(); )
			{
				BCADGraphCell pcell = itp.next();
				System.out.println(tab+"Discretizations of shape "+pcell);
				for (Iterator<BDiscretization> itpd = pcell.discretizationIterator(); itpd.hasNext(); )
				{
					BDiscretization pd = itpd.next();
					if (pcell == pd.getGraphCell())
						System.out.println(tab+"    + "+"Used discretization: "+pd.getId()+" with orientation: forward;  element type : "+pd.getConstraint().getHypothesis().getElement()+" constraint : "+pd.getConstraint().getId()+" submesh list : "+pd.getSubmesh());
					else if (pcell == pd.getGraphCell().getReversed() )
						System.out.println(tab+"    + "+"Used discretization: "+pd.getId()+" with orientation: reversed; element type : "+pd.getConstraint().getHypothesis().getElement()+" constraint : "+pd.getConstraint().getId()+" submesh list : "+pd.getSubmesh());
					else
						throw new RuntimeException("Invalid discretization "+pd+" on shape "+pcell);

 					if (cse != CADShapeEnum.VERTEX)
 					{
 						System.out.println(tab+"    + "+"    + "+"discretizations of boundary shapes: ");
						for (Iterator<BCADGraphCell> itc = pcell.shapesExplorer(normalChildType); itc.hasNext(); )
						{
							BCADGraphCell ccell = itc.next();
							for (Iterator<BDiscretization> itcd = ccell.discretizationIterator(); itcd.hasNext(); )
							{
								BDiscretization cd = itcd.next();
								if (pd.contained(cd))
								{
									if (ccell == cd.getGraphCell())
										System.out.println(tab+"    + "+"    + "+"    + "+"Used discretization: "+cd.getId()+" with orientation: forward;  element type : "+cd.getConstraint().getHypothesis().getElement()+" constraint : "+cd.getConstraint().getId()+" submesh list : "+cd.getSubmesh());
									else if (ccell == cd.getGraphCell().getReversed() )
										System.out.println(tab+"    + "+"    + "+"    + "+"Used discretization: "+cd.getId()+" with orientation: reversed; element type : "+cd.getConstraint().getHypothesis().getElement()+" constraint : "+cd.getConstraint().getId()+" submesh list : "+cd.getSubmesh());
									else
										throw new RuntimeException("Invalid discretization "+cd+" on shape "+ccell);
								}

							}
						}
						boolean first = true;
 						if (cse != CADShapeEnum.EDGE)
						{
							for (Iterator<CADShapeEnum> itccse = CADShapeEnum.iterator(CADShapeEnum.VERTEX, normalChildType); itccse.hasNext(); )
							{
								CADShapeEnum ccse = itccse.next();
								for (Iterator<BCADGraphCell> itc = pcell.shapesIterator(); itc.hasNext(); )
								{
									BCADGraphCell ccell = itc.next();
									if (ccell.getType() == ccse)
									{
										for (Iterator<BDiscretization> itcd = ccell.discretizationIterator(); itcd.hasNext(); )
										{
											BDiscretization cd = itcd.next();
											if (pd.contained(cd))
											{
												if (first)
												{
													System.out.println(tab+"    + "+"    + "+"discretizations of additionnal boundary shapes: ");
													first = false;
												}
												if (ccell == cd.getGraphCell())
													System.out.println(tab+"    + "+"    + "+"    + "+"    + "+"Used discretization: "+cd.getId()+" with orientation: forward;  element type : "+cd.getConstraint().getHypothesis().getElement()+" constraint : "+cd.getConstraint().getId()+" submesh list : "+cd.getSubmesh());
												else if (ccell == cd.getGraphCell().getReversed() )
													System.out.println(tab+"    + "+"    + "+"    + "+"    + "+"Used discretization: "+cd.getId()+" with orientation: reversed; element type : "+cd.getConstraint().getHypothesis().getElement()+" constraint : "+cd.getConstraint().getId()+" submesh list : "+cd.getSubmesh());
												else
													throw new RuntimeException("Invalid discretization "+cd+" on shape "+ccell);
											}
										}
									}
								}
							}
						}
					}
				}
			}
			indent.append("  ");
		}
		System.out.println("End list");
	}

	/**
	 * Prints the constraints applied to geometrical elements of the current mesh.
	 */
	public void printConstraints()
	{
		System.out.println("List of constraints");
		BCADGraphCell root = cad.getRootCell();
		StringBuilder indent = new StringBuilder();
		for (Iterator<CADShapeEnum> itcse = CADShapeEnum.iterator(CADShapeEnum.VERTEX, CADShapeEnum.COMPOUND); itcse.hasNext(); )
		{
			CADShapeEnum cse = itcse.next();
			String tab = indent.toString();
			for (Iterator<BCADGraphCell> it = root.shapesExplorer(cse); it.hasNext(); )
			{
				BCADGraphCell cell = it.next();
				boolean first = true;
				for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
				{
					BDiscretization d = itd.next();
					if (first)
						System.out.println(tab+"Shape "+cell);
					first = false;
					System.out.println(tab+"    + "+d);
				}
			}
			indent.append("  ");
		}
		System.out.println("End list");
	}

	/**
	 * Prints the constraints applied to a given submesh.
	 */
	public void printConstraints(BSubMesh sm)
	{
		System.out.println("List of constraints applied on submesh "+sm.getId());
		BCADGraphCell root = cad.getRootCell();
		StringBuilder indent = new StringBuilder();
		for (Iterator<CADShapeEnum> itcse = CADShapeEnum.iterator(CADShapeEnum.VERTEX, CADShapeEnum.COMPOUND); itcse.hasNext(); )
		{
			CADShapeEnum cse = itcse.next();
			String tab = indent.toString();
			for (Iterator<BCADGraphCell> it = root.shapesExplorer(cse); it.hasNext(); )
			{
				BCADGraphCell cell = it.next();
				for (Iterator<BDiscretization> itd = cell.discretizationIterator(); itd.hasNext(); )
				{
					BDiscretization d = itd.next();
					if (d.contains(sm))
					{
						System.out.println(tab+"Shape "+cell);
						System.out.println(tab+"    + "+d);
					}
				}
			}
			indent.append("  ");
		}
		System.out.println("End list");
	}

	// Sample test
	public static void main(String args[])
	{
		String file = "brep/2cubes.brep";

		BModel model = new BModel(file, "out");
		model.cad.printShapes();
		BModelWriter.writeObject(model);
	}
}
