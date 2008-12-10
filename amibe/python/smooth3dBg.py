
# jCAE
from org.jcae.mesh.amibe.ds import Mesh, Triangle
from org.jcae.mesh.amibe.algos3d import SmoothNodes3DBg
from org.jcae.mesh.amibe.traits import MeshTraitsBuilder
from org.jcae.mesh.xmldata import MeshReader, MeshWriter

# Java
from java.util import HashMap
from java.lang import String

# Python
import sys
from optparse import OptionParser

"""
Smooth mesh.
"""

parser = OptionParser(usage="amibebatch smooth3d [OPTIONS] <inputDir> <outputDir>\n\nPerform vertex smoothing on 3D mesh", prog="smooth3d")
parser.add_option("-i", "--iterations", metavar="NUMBER", default=1,
                  action="store", type="int", dest="iterations",
		  help="number of iterations (default: 1)")
parser.add_option("-s", "--size", metavar="FLOAT", default=-1.0,
                  action="store", type="float", dest="size",
                  help="target size")
parser.add_option("-t", "--tolerance", metavar="FLOAT", default=2.0,
                  action="store", type="float", dest="tolerance",
                  help="process only nodes with quality lower than this value")
parser.add_option("-r", "--relaxation", metavar="FLOAT", default=0.6,
                  action="store", type="string", dest="relaxation",
                  help="new position = old + r * (new - old)")
parser.add_option("-N", "--no-boundaries", action="store_false", dest="no_boundaries",
                  help="do not try to preserve patch boundaries")
parser.add_option("-R", "--refresh",
                  action="store_true", dest="refresh",
                  help="update triangle quality within loop")

(options, args) = parser.parse_args(args=sys.argv[1:])

if len(args) != 2:
	parser.print_usage()
	sys.exit(1)

xmlDir = args[0]
outDir = args[1]

mtb = MeshTraitsBuilder.getDefault3D()
mtb.addNodeList()
mesh = Mesh(mtb)
MeshReader.readObject3D(mesh, xmlDir)

opts = HashMap()
opts.put("iterations", str(options.iterations))
opts.put("boundaries", str(options.no_boundaries))
opts.put("size", str(options.size))
opts.put("tolerance", str(options.tolerance))
opts.put("relaxation", str(options.relaxation))
opts.put("refresh", str(options.refresh))
sm = SmoothNodes3DBg(mesh, opts)
sm.setProgressBarStatus(10000)
sm.compute()

MeshWriter.writeObject3D(sm.getOutputMesh(), outDir, String())

