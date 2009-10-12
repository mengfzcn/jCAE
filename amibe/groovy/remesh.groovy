/**
 * Remesh a mesh
 */
import org.jcae.mesh.amibe.ds.Mesh
import org.jcae.mesh.amibe.algos3d.Remesh
import org.jcae.mesh.amibe.traits.MeshTraitsBuilder
import org.jcae.mesh.xmldata.*
import org.apache.commons.cli.*;


cmd=["remesh  ", "Remesh an existing mesh"]
usage="<xmlDir>"

void usage(int rc, Options options)
{
	HelpFormatter formatter = new HelpFormatter();
	formatter.printHelp("amibebatch "+cmd[0].trim()+" [OPTIONS] "+usage, cmd[1], options, "");
	System.exit(rc);
}

Options options = new Options();
options.addOption(
	OptionBuilder.hasArg(false)
		.withDescription("usage information")
		.withLongOpt("help")
		.create('h'));
options.addOption(
	OptionBuilder.withArgName("VALUE").hasArg()
		.withDescription("angle (in degrees) to detect feature edges")
		.withLongOpt("ridgeAngle")
		.create('a'));
options.addOption(
	OptionBuilder.withArgName("VALUE").hasArg()
		.withDescription("target size")
		.withLongOpt("size")
		.create('t'));
options.addOption(
	OptionBuilder.withArgName("VALUE").hasArg()
		.withDescription("name of a file containing metrics map")
		.withLongOpt("metricsFile")
		.create('m'));
options.addOption(
	OptionBuilder.withArgName("DIR").hasArg()
		.withDescription("writes new mesh into this directory")
		.withLongOpt("output")
		.create('o'));

CommandLineParser parser = new GnuParser();
CommandLine cmd = parser.parse(options, args, true);
if (cmd.hasOption('h'))
	usage(0, options);

String [] remaining = cmd.getArgs();
if (remaining.length != 1)
	usage(1, options);
if (cmd.hasOption('t') && cmd.hasOption('m'))
{
	println("ERROR: -t and -m cannot be specified altogether")
	System.exit(1);
}

String xmldir = remaining[0]
String outDir = cmd.getOptionValue('o', xmldir)

MeshTraitsBuilder mtb = MeshTraitsBuilder.getDefault3D();
mtb.addNodeList();
Mesh mesh = new Mesh(mtb)
MeshReader.readObject3D(mesh, xmldir)

boolean setAnalytic = false
HashMap<String, String> algoOptions = new HashMap<String, String>();
if (cmd.hasOption('t'))
	algoOptions.put("size", cmd.getOptionValue('t'));
else if (cmd.hasOption('m'))
	algoOptions.put("metricsFile", cmd.getOptionValue('m'));
else
	setAnalytic = true
if (cmd.hasOption('a'))
	algoOptions.put("ridgeAngle", cmd.getOptionValue('a'));

Remesh algo = new Remesh(mesh, algoOptions)
public static class RemeshMetric implements Remesh.AnalyticMetricInterface
{
	public double getTargetSize(double x, double y, double z)
	{
		return Math.min(200.0, (x - 9000.0)*(x - 9000.0) / 2250.0);
	}
}

if (setAnalytic)
	algo.setAnalyticMetric(new RemeshMetric());
algo.compute();
MeshWriter.writeObject3D(algo.getOutputMesh(), outDir, null)

